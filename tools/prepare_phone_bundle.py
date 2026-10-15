"""Freeze the signed phone APKs included in the Windows EXE.

Build Android first, then run this script. --check verifies an existing bundle
without downloading files or replacing its stable APK signatures.
"""
import argparse
import hashlib
import json
import re
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'phone-app'
SHIZUKU_URL = ('https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/'
               'shizuku-v13.6.0.r1086.2650830c-release.apk')
SHIZUKU_SHA256 = '6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def validate_apk(path):
    with zipfile.ZipFile(path) as archive:
        if not {'AndroidManifest.xml', 'classes.dex'} <= set(archive.namelist()):
            raise ValueError('Invalid APK: %s (run git lfs pull if this is a pointer)' % path)


def check():
    nmap = json.loads((ROOT / 'nmap-bundle.json').read_text(encoding='utf-8'))
    for item in nmap['apks']:
        path = ROOT / item['file']
        with zipfile.ZipFile(path) as archive:
            if 'AndroidManifest.xml' not in archive.namelist():
                raise ValueError('Invalid Naver Map APK: ' + str(path))
        if sha256(path) != item['sha256']:
            raise ValueError('Naver Map checksum mismatch: ' + str(path))
    manifest = json.loads((DEST / 'bundle.json').read_text(encoding='utf-8'))
    for app in manifest['apps']:
        path = DEST / app['file']
        validate_apk(path)
        if sha256(path) != app['sha256']:
            raise ValueError('APK checksum mismatch: %s' % path)
    gradle = (ROOT / 'android/app/build.gradle').read_text(encoding='utf-8')
    version = int(re.search(r'versionCode\s+(\d+)', gradle).group(1))
    reset = next(a for a in manifest['apps'] if a['package'] == 'com.forholiday.galaxyreset')
    if reset['versionCode'] != version:
        raise ValueError('Rebuild Android and rerun tools/prepare_phone_bundle.py before building the EXE')
    with zipfile.ZipFile(DEST / reset['file']) as archive:
        embedded_nmap = json.loads(archive.read('assets/nmap-bundle.json'))
        if embedded_nmap != nmap:
            raise ValueError('Android APK contains an old Naver Map bundle; rebuild Android and refresh phone-app first')
    print('Phone APK bundle verified: Galaxy Reset %s, Shizuku 13.6.0' % reset['versionName'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--reset-apk', type=Path, default=ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--shizuku-apk', type=Path, help='Official v13.6.0 APK; otherwise downloaded with a pinned hash')
    args = parser.parse_args()
    if args.check:
        check()
        return
    metadata = json.loads((args.reset_apk.parent / 'output-metadata.json').read_text(encoding='utf-8'))
    if metadata['applicationId'] != 'com.forholiday.galaxyreset':
        raise ValueError('Unexpected Android applicationId')
    element = next(e for e in metadata['elements'] if e['outputFile'] == args.reset_apk.name)
    validate_apk(args.reset_apk)
    DEST.mkdir(exist_ok=True)
    shizuku = args.shizuku_apk or DEST / 'shizuku.apk.download'
    try:
        if args.shizuku_apk is None:
            urllib.request.urlretrieve(SHIZUKU_URL, shizuku)
        validate_apk(shizuku)
        if sha256(shizuku) != SHIZUKU_SHA256:
            raise ValueError('Official Shizuku APK hash mismatch')
        shutil.copyfile(args.reset_apk, DEST / 'galaxy-reset.apk')
        if shizuku.resolve() != (DEST / 'shizuku.apk').resolve():
            shutil.copyfile(shizuku, DEST / 'shizuku.apk')
    finally:
        if args.shizuku_apk is None:
            shizuku.unlink(missing_ok=True)
    manifest = {'apps': [
        {'package': 'com.forholiday.galaxyreset', 'file': 'galaxy-reset.apk',
         'versionCode': element['versionCode'], 'versionName': element['versionName'],
         'sha256': sha256(DEST / 'galaxy-reset.apk')},
        {'package': 'moe.shizuku.privileged.api', 'file': 'shizuku.apk',
         'versionCode': 1086, 'versionName': '13.6.0', 'source': SHIZUKU_URL,
         'sha256': SHIZUKU_SHA256},
    ]}
    (DEST / 'bundle.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    check()


if __name__ == '__main__':
    main()
