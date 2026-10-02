#!/usr/bin/env python3
"""Download a verified, isolated JDK/Gradle/Android toolchain to a given directory."""
import concurrent.futures
import json
from pathlib import Path
import sys
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from build_network import download_url, read_url

GRADLE_SHA256 = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '/private/tmp/kgx2mp3-toolchain')
ROOT.mkdir(parents=True, exist_ok=True)

def read(url):
    return read_url(url)

def download(url, name, checksum=None, algorithm='sha256'):
    target = ROOT / name
    if not target.exists(): print('Downloading ' + name, flush=True)
    return download_url(url, target, checksum, algorithm)

def jdk():
    dest = ROOT / 'jdk'
    if (dest / 'Contents/Home/bin/java').exists(): return
    data = json.loads(read('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=aarch64&image_type=jdk&os=mac&vendor=eclipse'))
    package = data[0]['binary']['package']
    archive = download(package['link'], 'jdk17.tar.gz', package['checksum'])
    staging = ROOT / 'jdk-extract'
    staging.mkdir(exist_ok=True)
    with tarfile.open(archive) as f:
        for member in f.getmembers():
            resolved = (staging / member.name).resolve()
            if staging.resolve() not in resolved.parents:
                raise RuntimeError('Unsafe archive member: ' + member.name)
        if sys.version_info >= (3, 12):
            f.extractall(staging, filter='data')
        else:
            f.extractall(staging)
    next(staging.iterdir()).rename(dest)
    staging.rmdir()
    print('JDK ready', flush=True)

def gradle():
    dest = ROOT / 'gradle-8.13'
    if dest.exists(): return
    url = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
    archive = download(url, 'gradle-8.13-bin.zip', GRADLE_SHA256)
    with zipfile.ZipFile(archive) as f: f.extractall(ROOT)
    (dest / 'bin/gradle').chmod(0o755)
    print('Gradle ready', flush=True)

def sdk():
    dest = ROOT / 'sdk/cmdline-tools/latest'
    if (dest / 'bin/sdkmanager').exists(): return
    repo = ET.fromstring(read('https://dl.google.com/android/repository/repository2-3.xml'))
    candidates = []
    for package in repo.findall('{*}remotePackage'):
        if not package.get('path', '').startswith('cmdline-tools;'): continue
        channel = package.find('{*}channelRef')
        if channel is not None and channel.get('ref') != 'channel-0': continue
        revision = package.find('{*}revision')
        major = int(revision.findtext('{*}major', '0'))
        for item in package.findall('{*}archives/{*}archive'):
            if item.findtext('{*}host-os') == 'macosx':
                complete = item.find('{*}complete')
                candidates.append((major, complete.findtext('{*}url'), complete.findtext('{*}checksum')))
    _, path, checksum = max(candidates)
    archive = download('https://dl.google.com/android/repository/' + path, 'android-commandline.zip', checksum, 'sha1')
    staging = ROOT / 'sdk-extract'
    staging.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as f: f.extractall(staging)
    dest.parent.mkdir(parents=True, exist_ok=True)
    (staging / 'cmdline-tools').rename(dest)
    staging.rmdir()
    for file in (dest / 'bin').iterdir(): file.chmod(0o755)
    print('Android command-line tools ready', flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    futures = [pool.submit(task) for task in (jdk, gradle, sdk)]
    for future in futures: future.result()
print('Toolchain ready at ' + str(ROOT), flush=True)
