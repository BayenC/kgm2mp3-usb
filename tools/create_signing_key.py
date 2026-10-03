#!/usr/bin/env python3
"""Create a private per-project Android signing key. Never commit the signing directory."""
from pathlib import Path
import os
import secrets
import subprocess

folder = Path('signing')
folder.mkdir(exist_ok=True)
folder.chmod(0o700)
properties = folder / 'kgm2mp3-usb.properties'
if properties.exists():
    print('Existing kgm2mp3-usb signing configuration preserved.')
    raise SystemExit(0)
key = folder / 'kgm2mp3-usb.jks'
if key.exists(): raise SystemExit('Key exists without properties; recover its configuration before proceeding.')
password = secrets.token_urlsafe(32)
env = os.environ.copy()
env['KGM_SIGN_PASSWORD'] = password
binary = Path(os.environ.get('JAVA_HOME', '/private/tmp/kgx2mp3-toolchain/jdk/Contents/Home')) / 'bin/keytool'
subprocess.run([str(binary), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12',
    '-storepass:env', 'KGM_SIGN_PASSWORD', '-keypass:env', 'KGM_SIGN_PASSWORD',
    '-alias', 'kgm2mp3-usb', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
    '-dname', 'CN=kgm2mp3-usb, OU=Personal Android App, O=kgm2mp3-usb, C=CN'], check=True, env=env,
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
properties.write_text('storeFile=signing/kgm2mp3-usb.jks\nstorePassword=' + password +
                      '\nkeyAlias=kgm2mp3-usb\nkeyPassword=' + password + '\n')
properties.chmod(0o600)
key.chmod(0o600)
print('Private kgm2mp3-usb signing key created; signing/ is excluded from version control.')
