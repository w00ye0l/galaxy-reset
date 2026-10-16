"""Prepare the exact signed APK and metadata consumed by the phone's self-updater."""
import argparse
import json
import re
import shutil
from pathlib import Path
from prepare_phone_bundle import ROOT, DEST, check


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', default='')
    parser.add_argument('--output', type=Path, default=ROOT / 'release-app')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    check()
    metadata = json.loads((DEST / 'galaxy-reset-update.json').read_text(encoding='utf-8'))
    version = metadata['versionName']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('App release version must be major.minor.patch')
    tag = args.tag or 'app-v' + version
    if tag != 'app-v' + version:
        raise ValueError('Release tag must match bundled APK: app-v' + version)
    args.output.mkdir(parents=True, exist_ok=True)
    for name in ['galaxy-reset.apk', 'galaxy-reset-update.json']:
        shutil.copyfile(DEST / name, args.output / name)
    title = '갤럭시 초기화 ' + version
    notes = ('갤럭시 초기화 Android 앱 ' + version + '\n\n'
             '앱의 업데이트 확인에서 다운로드하고 Android 설치 확인을 진행하세요.\n'
             '기존 설치와 동일한 서명의 APK를 사용하며, 앱 데이터와 Shizuku 권한을 유지하는 업데이트입니다.\n\n'
             'versionCode: ' + str(metadata['versionCode']) + '\nSHA-256: ' + metadata['sha256'] + '\n')
    (args.output / 'release-notes.txt').write_text(notes, encoding='utf-8')
    if args.github_output:
        with args.github_output.open('a', encoding='utf-8') as output:
            output.write('tag=' + tag + '\ntitle=' + title + '\n')
    print('App release prepared:', tag, args.output)


if __name__ == '__main__':
    main()
