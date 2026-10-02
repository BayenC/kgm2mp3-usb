#!/usr/bin/env python3
"""Optional emulator/image setup, with an explicitly selected host architecture."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from build_network import download_url, java_environment, read_url, sdkmanager_proxy_options


def host_target():
    host_os = {'Darwin': 'macosx', 'Linux': 'linux', 'Windows': 'windows'}.get(platform.system())
    if host_os is None:
        raise RuntimeError('Unsupported emulator host operating system')
    machine = platform.machine().lower()
    if host_os == 'macosx':
        # A Python process under Rosetta may report x86_64 on Apple Silicon.
        probe = subprocess.run(['sysctl', '-n', 'hw.optional.arm64'], capture_output=True, text=True)
        if probe.returncode == 0 and probe.stdout.strip() == '1':
            machine = 'arm64'
    host_arch = 'aarch64' if machine in ('aarch64', 'arm64') else 'x64' if machine in ('x86_64', 'amd64') else None
    if host_arch is None:
        raise RuntimeError('Unsupported emulator host CPU architecture')
    return host_os, host_arch


def select_archive(xml, host_os, host_arch):
    candidates = []
    for package in ET.fromstring(xml).findall('{*}remotePackage'):
        if package.get('path') != 'emulator':
            continue
        channel = package.find('{*}channelRef')
        if channel is None or channel.get('ref') != 'channel-0':
            continue
        revision = package.find('{*}revision')
        version = tuple(int(revision.findtext('{*}' + part, '0')) for part in ('major', 'minor', 'micro'))
        for archive in package.findall('{*}archives/{*}archive'):
            if archive.findtext('{*}host-os') != host_os or archive.findtext('{*}host-arch') != host_arch:
                continue
            complete = archive.find('{*}complete')
            checksum = complete.find('{*}checksum')
            candidates.append((version, complete.findtext('{*}url'), checksum.text, checksum.get('type', 'sha1')))
    if not candidates:
        raise RuntimeError('No stable emulator archive matches this host architecture')
    return max(candidates)


def emulator_matches(directory, host_arch):
    binary = directory / ('emulator.exe' if platform.system() == 'Windows' else 'emulator')
    if not binary.is_file():
        return False
    if platform.system() == 'Windows':
        return host_arch == 'x64'
    details = subprocess.run(['file', '-b', str(binary)], capture_output=True, text=True)
    markers = ('arm64', 'aarch64') if host_arch == 'aarch64' else ('x86_64', 'x86-64')
    return details.returncode == 0 and any(marker in details.stdout for marker in markers)


def install_host_emulator(root, host_os, host_arch):
    metadata = root / 'repository2-3.xml'
    if not metadata.exists():
        metadata.write_bytes(read_url('https://dl.google.com/android/repository/repository2-3.xml'))
    version, name, checksum, algorithm = select_archive(metadata.read_bytes(), host_os, host_arch)
    print('Preparing optional native emulator ' + '.'.join(map(str, version)) + ' (' + host_arch + ')', flush=True)
    archive = download_url('https://dl.google.com/android/repository/' + name, root / name, checksum, algorithm)
    staging = Path(tempfile.mkdtemp(prefix='emulator-extract-', dir=root))
    try:
        with zipfile.ZipFile(archive) as package:
            for member in package.infolist():
                destination = staging / member.filename
                if staging.resolve() not in destination.resolve().parents:
                    raise RuntimeError('Unsafe emulator archive member')
                mode = member.external_attr >> 16
                if stat.S_ISLNK(mode):
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    target = package.read(member).decode()
                    if staging.resolve() not in (destination.parent / target).resolve().parents:
                        raise RuntimeError('Unsafe emulator archive link')
                    os.symlink(target, destination)
                else:
                    package.extract(member, staging)
                    if not member.is_dir() and mode:
                        destination.chmod(stat.S_IMODE(mode))
        replacement = staging / 'emulator'
        if not emulator_matches(replacement, host_arch):
            raise RuntimeError('Downloaded emulator does not match the host CPU')
        destination = root / 'sdk/emulator'
        backup = None
        if destination.exists():
            backup = Path(tempfile.mkdtemp(prefix='emulator-previous-', dir=root))
            backup.rmdir()
            destination.rename(backup)
        try:
            replacement.rename(destination)
        except BaseException:
            if backup is not None:
                backup.rename(destination)
            raise
        if backup is not None:
            shutil.rmtree(backup)
    finally:
        shutil.rmtree(staging)


def prepare_emulator(root, source_env=None):
    root = Path(root).expanduser().resolve()
    host_os, host_arch = host_target()
    env = java_environment(source_env)
    env.update(JAVA_HOME=str(root / 'jdk/Contents/Home'), ANDROID_HOME=str(root / 'sdk'),
               ANDROID_SDK_ROOT=str(root / 'sdk'), ANDROID_USER_HOME=str(root / 'android-user'))
    # Some SDK CLI versions choose darwin_x64 on Apple Silicon even though an
    # aarch64 archive exists. Select and verify the official archive ourselves.
    if not emulator_matches(root / 'sdk/emulator', host_arch):
        install_host_emulator(root, host_os, host_arch)
    image_arch = 'arm64-v8a' if host_arch == 'aarch64' else 'x86_64'
    image = 'system-images;android-33;google_apis;' + image_arch
    command = [str(root / 'sdk/cmdline-tools/latest/bin/sdkmanager'), '--sdk_root=' + str(root / 'sdk'),
               *sdkmanager_proxy_options(env), image]
    log = root / 'emulator-install.log'
    print('Installing optional emulator image: ' + image, flush=True)
    with log.open('a') as output:
        result = subprocess.run(command, input='y\n' * 20, text=True, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError('Optional emulator image failed; APK build setup remains ready. See ' + str(log))
    if not emulator_matches(root / 'sdk/emulator', host_arch):
        install_host_emulator(root, host_os, host_arch)
    print('Optional emulator ready (' + host_arch + ').', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('toolchain', nargs='?', default='/private/tmp/kgx2mp3-toolchain')
    prepare_emulator(Path(parser.parse_args().toolchain))
