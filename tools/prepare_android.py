#!/usr/bin/env python3
"""Prepare build essentials first; optional emulator work cannot block APK setup."""
import argparse
from pathlib import Path
import subprocess

from build_network import java_environment, sdkmanager_proxy_options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('toolchain', nargs='?', default='/private/tmp/kgx2mp3-toolchain')
    parser.add_argument('--emulator', action='store_true', help='Also prepare the optional host-native emulator after build setup')
    arguments = parser.parse_args()
    root = Path(arguments.toolchain).expanduser().resolve()
    project = Path(__file__).resolve().parents[1]
    env = java_environment()
    env.update(JAVA_HOME=str(root / 'jdk/Contents/Home'), ANDROID_HOME=str(root / 'sdk'),
               ANDROID_SDK_ROOT=str(root / 'sdk'), ANDROID_USER_HOME=str(root / 'android-user'))
    sdkmanager = str(root / 'sdk/cmdline-tools/latest/bin/sdkmanager')
    root.joinpath('android-user').mkdir(parents=True, exist_ok=True)
    log = root / 'sdk-install.log'
    command = [sdkmanager, '--sdk_root=' + str(root / 'sdk'), *sdkmanager_proxy_options(env)]
    with log.open('a') as output:
        result = subprocess.run([*command, '--licenses'], input='y\n' * 20, text=True,
                                env=env, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit('SDK licenses failed; see ' + str(log))
        components = ['platforms;android-35', 'build-tools;35.0.0', 'platform-tools']
        print('Installing build essentials: ' + ', '.join(components), flush=True)
        result = subprocess.run([*command, *components], input='y\n' * 20, text=True,
                                env=env, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit('SDK build essentials failed; see ' + str(log))
    # Write this before any optional multi-hundred-MB emulator/image download.
    sdk_path = str(root / 'sdk').replace('\\', '\\\\').replace(':', '\\:')
    (project / 'local.properties').write_text('sdk.dir=' + sdk_path + '\n')
    print('Android build SDK ready. Optional emulator is not required to build APKs. Log: ' + str(log), flush=True)
    if arguments.emulator:
        from prepare_emulator import prepare_emulator
        prepare_emulator(root, env)


if __name__ == '__main__':
    main()
