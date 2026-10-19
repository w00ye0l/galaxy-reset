"""Prepare the exact signed APK and metadata consumed by the phone's self-updater."""
import argparse
import json
import re
import shutil
import struct
import zipfile
from pathlib import Path
from prepare_phone_bundle import ROOT, DEST, check, sha256


def package_windows(exe, output, version):
    with exe.open('rb') as source:
        header = source.read(64)
        if len(header) != 64 or header[:2] != b'MZ':
            raise ValueError('Windows EXE is not a PE executable')
        offset = struct.unpack_from('<I', header, 60)[0]
        if offset < 64 or offset > exe.stat().st_size - 4:
            raise ValueError('Invalid Windows EXE header')
        source.seek(offset)
        if source.read(4) != b'PE\0\0':
            raise ValueError('Invalid Windows PE signature')
    archive = output / ('galaxy-reset-windows-' + version + '.zip')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
        bundle.write(exe, arcname=exe.name)
    digest = sha256(archive)
    archive.with_suffix('.zip.sha256').write_text(digest + '  ' + archive.name + '\n', encoding='utf-8')
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', default='')
    parser.add_argument('--output', type=Path, default=ROOT / 'release-app')
    parser.add_argument('--github-output', type=Path)
    parser.add_argument('--exe', type=Path, help='Built Windows EXE to include in the same app release')
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
    if args.exe:
        archive = package_windows(args.exe, args.output, version)
        notes += ('\nWindows PC 프로그램: `' + archive.name + '`을 내려받아 압축을 풀고 EXE를 실행하세요.\n'
                  '초기화 앱 ' + version + '과 Shizuku가 동봉되어 있습니다. 최초 USB 준비 시 앱 전용 무선 페어링을 자동 등록합니다.\n'
                  '최초 USB 디버깅 승인과 기기 잠금 해제는 직접 해야 하며, 이후 등록한 Wi-Fi에서 초기화 앱을 열면 Shizuku 시작과 권한 연결을 시도합니다.\n')
    changelog = ROOT / 'release-notes' / (tag + '.md')
    if changelog.is_file():
        notes += '\n' + changelog.read_text(encoding='utf-8')
    (args.output / 'release-notes.txt').write_text(notes, encoding='utf-8')
    if args.github_output:
        with args.github_output.open('a', encoding='utf-8') as output:
            output.write('tag=' + tag + '\ntitle=' + title + '\n')
    print('App release prepared:', tag, args.output)


if __name__ == '__main__':
    main()
