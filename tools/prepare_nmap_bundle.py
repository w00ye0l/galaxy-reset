"""Import a complete, publisher-signed Naver Map APK set without changing signatures."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.nhn.android.nmap'
# Publisher certificate verified against the previously bundled Naver Map APK.
CERTIFICATE = '0b8b8523bb4aeffa346e4bdd4fbf7d193450569aa14aaad4adfd94a3f7b227bb'


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-directory', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--source', required=True, help='Actual download or store provenance')
    args = parser.parse_args()
    verified = []
    base = None
    for apk in sorted(args.apk_directory.glob('*.apk')):
        signed = subprocess.run([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)],
                                capture_output=True, text=True, check=True)
        certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', signed.stdout)
        if certs != [CERTIFICATE]:
            raise ValueError('Naver publisher certificate mismatch: ' + apk.name)
        # Split base APKs may lack icon resources until the density split is installed.
        badging = subprocess.run([str(args.build_tools / 'aapt'), 'dump', 'badging', str(apk)],
                                 capture_output=True, text=True)
        line = next(line for line in badging.stdout.splitlines() if line.startswith('package:'))
        fields = dict(re.findall(r"(\w+)='([^']*)'", line))
        if fields['name'] != PACKAGE:
            raise ValueError('Unexpected package: ' + apk.name)
        split = fields.get('split', '')
        if split and not re.fullmatch(r'[A-Za-z0-9_.]+', split):
            raise ValueError('Invalid split name')
        filename = 'nmap-' + split + '.apk' if split else 'nmap.apk'
        verified.append((apk, fields, filename))
        if not split:
            if base is not None:
                raise ValueError('Multiple base APKs')
            base = fields
    if base is None or any(fields['versionCode'] != base['versionCode'] for _, fields, _ in verified):
        raise ValueError('Missing base APK or mixed versions')
    for apk, _, filename in verified:
        shutil.copyfile(apk, ROOT / filename)
    files = [{'file': filename, 'sha256': digest(ROOT / filename), 'size': (ROOT / filename).stat().st_size}
             for _, _, filename in verified]
    files.sort(key=lambda item: (item['file'] != 'nmap.apk', item['file']))
    metadata = {'package': PACKAGE, 'versionCode': int(base['versionCode']), 'versionName': base['versionName'],
                'signingCertificateSha256': CERTIFICATE, 'source': args.source, 'apks': files}
    (ROOT / 'nmap-bundle.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Naver Map %s: %d publisher-signed APKs imported' % (base['versionName'], len(files)))


if __name__ == '__main__':
    main()
