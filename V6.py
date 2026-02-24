import subprocess
import os
import sys
import logging
import json
import threading
import time

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)


def resource_path(relative_path):
    """exe로 빌드된 경우에도 리소스 파일을 찾을 수 있도록 경로 반환"""
    if hasattr(sys, '_MEIPASS'):
        return os.path.join(sys._MEIPASS, relative_path)
    return os.path.join(os.path.abspath('.'), relative_path)


def run_command(cmd, check=False):
    """주어진 명령어를 실행하고 결과를 반환합니다."""
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=check)
        return result
    except subprocess.CalledProcessError as e:
        logging.error('명령어 실행 실패: %s / %s', ' '.join(cmd), e.stderr)
        return e


def get_connected_devices():
    """연결된 ADB 디바이스 목록을 가져옵니다."""
    output = subprocess.check_output(['adb', 'devices']).decode('utf-8')
    devices = []
    for line in output.strip().splitlines()[1:]:
        parts = line.strip().split('\t')
        if len(parts) == 2 and parts[1] == 'device':
            devices.append(parts[0])
    return devices


def detect_series_from_serial(serial_number, mapping_file='device_serial_map.json'):
    """시리얼 번호를 기반으로 디바이스 시리즈를 감지합니다."""
    path = resource_path(mapping_file)
    try:
        with open(path, 'r', encoding='utf-8') as f:
            mapping = json.load(f)
        if serial_number in mapping:
            series = mapping[serial_number]
            logging.info('[%s] 시리즈 감지: %s', serial_number, series)
            return series
        logging.warning('[%s] 시리얼 매핑 없음 — 기본값 S24 사용', serial_number)
        return 'S24'
    except Exception as e:
        logging.warning('[%s] 매핑 파일 로드 실패: %s — 기본값 S24 사용', serial_number, e)
        return 'S24'


def clear_app_data(serial, package, desc):
    """특정 앱의 데이터를 초기화합니다."""
    logging.info('[%s] %s 데이터 초기화 중...', serial, desc)
    run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', package])


# ============================================================
# 1. 언어 설정
# ============================================================

LANGUAGE_OPTIONS = {
    '1': {'locale': 'ja-JP', 'name': '日本語 (일본어)'},
    '2': {'locale': 'en-US', 'name': 'English (영어)'},
    '3': {'locale': 'ko-KR', 'name': '한국어'},
    '4': {'locale': 'zh-CN', 'name': '中文简体 (중국어 간체)'},
    '5': {'locale': 'zh-TW', 'name': '中文繁體 (중국어 번체)'},
}


def select_language():
    """사용자에게 언어 선택 메뉴를 표시합니다."""
    print('\n========================================')
    print('  언어 설정을 선택해주세요')
    print('========================================')
    for key, lang in LANGUAGE_OPTIONS.items():
        print(f'  {key}. {lang["name"]}')
    print('  0. 언어 변경 안함 (건너뛰기)')
    print('========================================')

    while True:
        choice = input('번호 입력: ').strip()
        if choice == '0':
            return None
        if choice in LANGUAGE_OPTIONS:
            selected = LANGUAGE_OPTIONS[choice]
            print(f'  → {selected["name"]} 선택됨\n')
            return selected['locale']
        print('  잘못된 입력입니다. 다시 선택해주세요.')


def set_device_language(serial, locale):
    """ADB를 통해 기기 언어를 변경합니다 (비루트 호환)."""
    if not locale:
        return
    logging.info('[%s] 언어 설정 변경: %s', serial, locale)

    # Step 1: cmd locale (Android 13+, S23/S24/S25 모두 해당)
    result = run_command([
        'adb', '-s', serial, 'shell',
        'cmd', 'locale', 'set-default', locale
    ])

    # Step 2: settings put 조합 (폴백)
    run_command([
        'adb', '-s', serial, 'shell',
        'settings', 'put', 'system', 'system_locales', locale
    ])

    # Step 3: persist.sys.locale 시도 (적용될 수도 있음)
    run_command([
        'adb', '-s', serial, 'shell',
        'setprop', 'persist.sys.locale', locale
    ])

    # Step 4: 로케일 변경 브로드캐스트
    run_command([
        'adb', '-s', serial, 'shell',
        'am', 'broadcast', '-a', 'android.intent.action.LOCALE_CHANGED'
    ])

    # Step 5: SystemUI 재시작으로 변경 강제 적용
    run_command([
        'adb', '-s', serial, 'shell',
        'am', 'force-stop', 'com.android.systemui'
    ])
    time.sleep(2)

    logging.info('[%s] 언어 설정 완료: %s', serial, locale)


# ============================================================
# 2. 계정 관리: 삼성 계정 제외 모든 계정 삭제
# ============================================================

# 삼성 계정 타입 (보존)
SAMSUNG_ACCOUNT_TYPES = [
    'com.osp.app.signin',           # 삼성 계정 기본
    'com.samsung.android.mobileservice',  # 삼성 모바일 서비스
    'com.samsung',                   # 삼성 공통 prefix
]

# 계정 타입 → 관련 앱 패키지 매핑 (pm clear 대상)
ACCOUNT_APPS = {
    'com.google': [
        'com.google.android.gms',
        'com.google.android.gsf',
        'com.google.android.gsf.login',
    ],
    'com.kakao': [
        'com.kakao.talk',
        'com.kakao.sdk',
    ],
    'com.nhn': [
        'com.nhn.android.search',
    ],
    'com.facebook': [
        'com.facebook.katana',
        'com.facebook.orca',
    ],
    'com.microsoft': [
        'com.microsoft.teams',
        'com.microsoft.office.outlook',
    ],
}


def get_device_accounts(serial):
    """기기에 등록된 계정 목록을 조회합니다."""
    result = run_command([
        'adb', '-s', serial, 'shell',
        'dumpsys', 'account'
    ])
    if not hasattr(result, 'stdout') or not result.stdout:
        return []

    accounts = []
    for line in result.stdout.splitlines():
        line = line.strip()
        # "Account {name=xxx, type=yyy}" 형태를 파싱
        if line.startswith('Account {') and 'type=' in line:
            try:
                type_part = line.split('type=')[1].rstrip('}').strip()
                name_part = line.split('name=')[1].split(',')[0].strip()
                accounts.append({'name': name_part, 'type': type_part})
            except (IndexError, ValueError):
                continue
    return accounts


def is_samsung_account(account_type):
    """삼성 계정인지 확인합니다."""
    for samsung_type in SAMSUNG_ACCOUNT_TYPES:
        if account_type.startswith(samsung_type):
            return True
    return False


def remove_non_samsung_accounts(serial):
    """삼성 계정을 제외한 모든 계정을 삭제합니다 (비루트 호환)."""
    logging.info('[%s] 삼성 계정 제외 전체 계정 삭제 시작...', serial)

    accounts = get_device_accounts(serial)
    if not accounts:
        logging.info('[%s] 등록된 계정이 없거나 조회 실패', serial)

    samsung_count = 0
    removed_types = set()

    for account in accounts:
        if is_samsung_account(account['type']):
            samsung_count += 1
            logging.info('[%s] 삼성 계정 보존: %s (%s)', serial, account['name'], account['type'])
        else:
            logging.info('[%s] 계정 삭제 대상: %s (%s)', serial, account['name'], account['type'])
            removed_types.add(account['type'])

    logging.info('[%s] 삼성 계정 %d개 보존, 삭제 대상 타입 %d개', serial, samsung_count, len(removed_types))

    # Step 1: 계정 관련 핵심 시스템 provider 초기화
    logging.info('[%s] 계정 관련 시스템 provider 초기화 중...', serial)
    clear_app_data(serial, 'com.google.android.gms', 'Google Play 서비스')
    clear_app_data(serial, 'com.google.android.gsf', 'Google Services Framework')
    clear_app_data(serial, 'com.android.providers.contacts', '연락처 Provider')
    clear_app_data(serial, 'com.android.contacts', '연락처')

    # Step 2: 계정 관련 앱 강제 종료
    logging.info('[%s] 계정 관련 앱 강제 종료 중...', serial)
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.google.android.gms'])
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.google.android.gsf'])

    # Step 3: 발견된 계정 타입에 매핑되는 앱 데이터 초기화
    cleared_packages = set()
    for account_type in removed_types:
        for prefix, packages in ACCOUNT_APPS.items():
            if account_type.startswith(prefix):
                for pkg in packages:
                    if pkg not in cleared_packages:
                        clear_app_data(serial, pkg, pkg)
                        run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', pkg])
                        cleared_packages.add(pkg)

    # Step 4: GSF 로그인 disable/enable 사이클 (계정 연결 해제 강제)
    logging.info('[%s] GSF 로그인 disable/enable 사이클...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'pm', 'disable-user', '--user', '0', 'com.google.android.gsf.login'
    ])
    time.sleep(1)
    run_command([
        'adb', '-s', serial, 'shell',
        'pm', 'enable', 'com.google.android.gsf.login'
    ])

    # Step 5: GMS 패키지도 disable/enable 사이클
    logging.info('[%s] GMS disable/enable 사이클...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'pm', 'disable-user', '--user', '0', 'com.google.android.gms'
    ])
    time.sleep(1)
    run_command([
        'adb', '-s', serial, 'shell',
        'pm', 'enable', 'com.google.android.gms'
    ])

    # Step 6: 계정 확인 — 남아있으면 로그 경고
    time.sleep(2)
    remaining = get_device_accounts(serial)
    non_samsung_remaining = [a for a in remaining if not is_samsung_account(a['type'])]
    if non_samsung_remaining:
        logging.warning('[%s] 아직 남아있는 비삼성 계정 %d개:', serial, len(non_samsung_remaining))
        for a in non_samsung_remaining:
            logging.warning('[%s]   - %s (%s)', serial, a['name'], a['type'])
    else:
        logging.info('[%s] 비삼성 계정 모두 제거 완료', serial)

    logging.info('[%s] 삼성 계정 제외 전체 계정 삭제 완료', serial)


# ============================================================
# 2. 갤러리 휴지통 완전 정리
# ============================================================

def deep_clean_gallery_trash(serial):
    """삼성 갤러리 휴지통을 완전히 정리합니다."""
    logging.info('[%s] 갤러리 휴지통 완전 정리 시작...', serial)

    # Step A: 갤러리 앱 강제 종료
    logging.info('[%s] 갤러리 앱 강제 종료', serial)
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.gallery3d'])
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.app.myfiles'])
    time.sleep(1)

    # Step B: 휴지통 물리 파일 삭제 (확장된 경로 목록)
    logging.info('[%s] 휴지통 물리 파일 삭제 중...', serial)
    trash_paths = [
        # 삼성 갤러리 휴지통
        '/sdcard/Android/data/com.sec.android.gallery3d/files/Trash',
        '/sdcard/Android/data/com.sec.android.gallery3d/files/.Trash',
        '/sdcard/Android/data/com.sec.android.gallery3d/cache',
        # 삼성 내 파일 휴지통
        '/sdcard/Android/data/com.sec.android.app.myfiles/files/Trash',
        '/sdcard/Android/data/com.sec.android.app.myfiles/files/.Trash',
        # 삼성 스튜디오 휴지통
        '/sdcard/Android/data/com.sec.android.app.vepreload/files/Trash',
        # 시스템 휴지통
        '/sdcard/.Trash',
        '/sdcard/.Trash-0',
        '/sdcard/Recycle',
        # 썸네일 캐시 (갤러리가 이걸 참조하여 삭제된 이미지 표시)
        '/sdcard/DCIM/.thumbnails',
        '/sdcard/Pictures/.thumbnails',
        '/sdcard/Android/data/com.sec.android.gallery3d/files/.thumbnails',
        # 추가 캐시 경로
        '/sdcard/Android/data/com.android.chrome/cache',
        '/sdcard/Android/data/dji.mimo/cache',
        '/sdcard/Android/data/com.nhn.android.nmap/cache',
    ]
    for path in trash_paths:
        run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', path])

    # Step C: MediaStore에서 휴지통 레코드 삭제
    logging.info('[%s] MediaStore 휴지통 레코드 삭제 중...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'content', 'delete',
        '--uri', 'content://media/external/file',
        '--where', 'is_trashed=1'
    ])
    run_command([
        'adb', '-s', serial, 'shell',
        'content', 'delete',
        '--uri', 'content://media/external/images/media',
        '--where', 'is_trashed=1'
    ])
    run_command([
        'adb', '-s', serial, 'shell',
        'content', 'delete',
        '--uri', 'content://media/external/video/media',
        '--where', 'is_trashed=1'
    ])

    # Step D: 갤러리 + 파일앱 데이터 초기화 (썸네일 캐시 DB 포함)
    logging.info('[%s] 갤러리/파일앱 데이터 초기화 중...', serial)
    clear_app_data(serial, 'com.sec.android.gallery3d', '삼성 갤러리')
    clear_app_data(serial, 'com.sec.android.app.myfiles', '내 파일')

    # Step E: MediaStore 전체 리프레시
    logging.info('[%s] MediaStore 리프레시 중...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'am', 'broadcast', '-a', 'android.intent.action.MEDIA_MOUNTED',
        '-d', 'file:///sdcard'
    ])
    time.sleep(2)

    logging.info('[%s] 갤러리 휴지통 완전 정리 완료', serial)


# ============================================================
# 3. 기존 기능 (V5에서 유지)
# ============================================================

def delete_user_installed_apps(serial):
    """제외 목록을 제외한 사용자가 설치한 앱을 삭제합니다."""
    exclude_apps = [
        'com.sec.android.app.popupcalculator',
        'com.nhn.android.nmap',
        'dji.mimo',
        'com.alphainventor.filemanager',
        'net.dinglisch.android.taskerm',
    ]
    output = subprocess.check_output([
        'adb', '-s', serial, 'shell',
        'pm', 'list', 'packages', '--user', '0', '-3'
    ]).decode('utf-8')
    installed_apps = [line.replace('package:', '').strip() for line in output.splitlines() if line.strip()]

    for app in installed_apps:
        if app not in exclude_apps:
            logging.info('[%s] 앱 삭제: %s', serial, app)
            run_command(['adb', '-s', serial, 'shell', 'pm', 'uninstall', '--user', '0', app])
        else:
            logging.info('[%s] 앱 보존: %s', serial, app)


def clear_google_apps_history(serial):
    """Google 앱 사용 기록을 삭제합니다."""
    google_apps = {
        'com.google.android.googlequicksearchbox': 'Google 검색',
        'com.android.chrome': 'Chrome',
        'com.google.android.youtube': 'YouTube',
        'com.google.android.gm': 'Gmail',
        'com.google.android.apps.maps': 'Google 지도',
        'com.google.android.apps.docs': 'Google 드라이브',
        'com.google.android.calendar': 'Google 캘린더',
        'com.google.android.apps.photos': 'Google 포토',
    }
    logging.info('[%s] Google 앱 사용 기록 삭제 시작...', serial)
    for package, name in google_apps.items():
        clear_app_data(serial, package, name)
    logging.info('[%s] Google 앱 사용 기록 삭제 완료.', serial)


def clear_logs_and_cache(serial):
    """로그, 통화기록, 주소록, 앱 캐시를 삭제합니다."""
    clear_app_data(serial, 'com.android.providers.telephony', 'SMS')

    logging.info('[%s] 통화 기록 삭제 중...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'content', 'delete', '--uri', 'content://call_log/calls'
    ])

    logging.info('[%s] 주소록 삭제 중...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'content', 'delete', '--uri', 'content://contacts/people'
    ])

    clear_app_data(serial, 'com.sec.android.gallery3d', '갤러리')
    clear_app_data(serial, 'dji.mimo', 'Mimo')
    clear_app_data(serial, 'com.nhn.android.nmap', 'Nmap')
    clear_app_data(serial, 'com.sec.android.themestore', '테마')
    clear_app_data(serial, 'com.sec.android.app.vepreload', '삼성 스튜디오')


def clear_media_store(serial):
    """MediaStore DB를 정리합니다."""
    logging.info('[%s] MediaStore DB 정리 중...', serial)
    run_command([
        'adb', '-s', serial, 'shell',
        'content', 'delete', '--uri', 'content://media/external/file'
    ])
    logging.info('[%s] MediaStore DB 정리 완료', serial)


def push_default_wallpaper(serial, wallpaper_file):
    """기본 배경화면을 기기에 푸시합니다."""
    image_path = resource_path(wallpaper_file)
    if not os.path.exists(image_path):
        logging.warning('[%s] 배경화면 파일 없음: %s', serial, image_path)
        return

    remote_path = f'/sdcard/DCIM/ForHoliday/{wallpaper_file}'
    run_command(['adb', '-s', serial, 'shell', 'mkdir', '-p', '/sdcard/DCIM/ForHoliday'])
    run_command(['adb', '-s', serial, 'push', image_path, remote_path])
    run_command([
        'adb', '-s', serial, 'shell',
        'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE',
        '-d', f'file://{remote_path}'
    ])
    logging.info('[%s] 배경화면 푸시 완료', serial)


def trigger_tasker_task(serial):
    """Tasker 자동화 작업을 트리거합니다."""
    run_command([
        'adb', '-s', serial, 'shell',
        'am', 'broadcast', '-a', 'com.example.CHANGE_SETTINGS'
    ])
    logging.info('[%s] Tasker 트리거 완료', serial)


def wipe_internal_storage(serial):
    """내장 메모리 전체를 삭제합니다."""
    logging.info('[%s] 내장 메모리 전체 삭제 시작', serial)
    run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', '/storage/emulated/0/*'])
    run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', '/storage/emulated/0/.*'])
    logging.info('[%s] 내장 메모리 전체 삭제 완료', serial)


def ensure_essential_apps_installed(serial):
    """필수 앱이 설치되어 있는지 확인하고 없으면 설치합니다."""
    apps = [
        {'package': 'com.nhn.android.nmap', 'name': 'Nmap', 'apk_path': resource_path('nmap.apk')},
        {'package': 'dji.mimo', 'name': 'Mimo', 'apk_path': resource_path('dji_mimo.apk')},
        {'package': 'com.alphainventor.filemanager', 'name': 'File Manager', 'apk_path': resource_path('filemanager.apk')},
        {'package': 'net.dinglisch.android.taskerm', 'name': 'Tasker', 'apk_path': resource_path('Tasker.apk')},
    ]
    output = subprocess.check_output([
        'adb', '-s', serial, 'shell', 'pm', 'list', 'packages'
    ]).decode('utf-8')

    for app in apps:
        if f"package:{app['package']}" in output:
            logging.info('[%s] %s 이미 설치됨', serial, app['name'])
        else:
            apk = app['apk_path']
            if os.path.exists(apk):
                logging.info('[%s] %s 설치 중...', serial, app['name'])
                run_command(['adb', '-s', serial, 'install', '-r', apk])
                logging.info('[%s] %s 설치 완료', serial, app['name'])
            else:
                logging.warning('[%s] APK 파일 없음: %s', serial, apk)


# ============================================================
# 4. 메인 프로세스
# ============================================================

def process_device(serial, locale=None):
    """단일 기기에 대한 전체 초기화 프로세스를 실행합니다."""
    logging.info('========================================')
    logging.info('[%s] 초기화 시작', serial)
    logging.info('========================================')

    series = detect_series_from_serial(serial)
    wallpaper = f'{series}.png' if series in ('S23', 'S24', 'S25') else 'S24.png'

    # [V6] 언어 설정 변경
    set_device_language(serial, locale)

    # [V6] 삼성 계정 제외 모든 계정 삭제
    remove_non_samsung_accounts(serial)
    clear_google_apps_history(serial)
    delete_user_installed_apps(serial)
    wipe_internal_storage(serial)
    clear_logs_and_cache(serial)

    # [V6] 갤러리 휴지통 완전 정리
    deep_clean_gallery_trash(serial)

    clear_media_store(serial)
    push_default_wallpaper(serial, wallpaper)
    trigger_tasker_task(serial)
    ensure_essential_apps_installed(serial)

    logging.info('========================================')
    logging.info('[%s] 초기화 완료', serial)
    logging.info('========================================')


def main():
    while True:
        devices = get_connected_devices()
        if not devices:
            logging.error('연결된 기기가 없습니다. ADB 연결을 확인해주세요.')
        else:
            logging.info('총 연결된 기기 수: %d', len(devices))
            for device in devices:
                logging.info(' - %s', device)

            # [V6] 언어 선택 메뉴
            locale = select_language()

            threads = []
            for serial in devices:
                t = threading.Thread(target=process_device, args=(serial, locale))
                t.start()
                threads.append(t)

            for t in threads:
                t.join()

            logging.info('모든 기기 초기화 작업이 완료되었습니다.')

        try:
            logging.info('추가로 작업하실 기기를 연결 완료 후 엔터를 눌러주세요. (종료하려면 Ctrl+C)')
            input()
        except KeyboardInterrupt:
            logging.info('프로그램을 종료합니다.')
            break


if __name__ == '__main__':
    main()
