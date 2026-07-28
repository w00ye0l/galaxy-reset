import subprocess
import os
import re
import sys
import logging
import json
import time
import shutil
import threading
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed


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


# ============================================================
# 0. 진행률 콘솔
# ============================================================

def _enable_ansi():
    """Windows 콘솔에서 ANSI 이스케이프(색/커서 이동)를 활성화합니다."""
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
    if os.name != 'nt':
        return True
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.GetStdHandle(-11)  # STD_OUTPUT_HANDLE
        mode = ctypes.c_uint32()
        if not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        # ENABLE_VIRTUAL_TERMINAL_PROCESSING
        return bool(kernel32.SetConsoleMode(handle, mode.value | 0x0004))
    except Exception:
        return False


def _display_width(text):
    """한글 등 전각 문자를 2칸으로 계산한 표시 폭."""
    return sum(2 if unicodedata.east_asian_width(ch) in ('W', 'F') else 1 for ch in text)


def _trim_to_width(text, limit):
    """표시 폭 기준으로 문자열을 자릅니다(줄바꿈으로 커서 계산이 깨지는 것 방지)."""
    if _display_width(text) <= limit:
        return text
    out, width = [], 0
    for ch in text:
        char_width = 2 if unicodedata.east_asian_width(ch) in ('W', 'F') else 1
        if width + char_width > limit:
            break
        out.append(ch)
        width += char_width
    return ''.join(out)


class ProgressConsole:
    """기기별 초기화 진행률을 콘솔에 제자리 갱신으로 표시합니다.

    5개 스레드가 동시에 진행 상황을 보고하지만 화면에 그리는 주체는
    내부 렌더 스레드 하나뿐입니다. 각 워커는 락 안에서 상태만 갱신하고,
    커서를 움직이는 것은 렌더 스레드가 전담해 출력이 섞이지 않습니다.
    """

    RESET = '\x1b[0m'
    DIM = '\x1b[90m'
    WHITE = '\x1b[97m'
    GREEN = '\x1b[92m'
    CYAN = '\x1b[96m'
    YELLOW = '\x1b[93m'
    RED = '\x1b[91m'
    SPINNER = '|/-\\'

    def __init__(self, total_steps):
        self.total_steps = total_steps
        self.states = {}
        self.order = []
        self.lock = threading.Lock()
        self.active = False
        self.started_at = 0.0
        self.lines_drawn = 0
        self.render_thread = None
        self.stop_event = threading.Event()
        self.console_handler = None
        # 콘솔 코드페이지가 UTF-8이 아니면 블록 문자가 깨지므로 ASCII로 대체
        self.fill_char, self.empty_char = self._pick_bar_chars()

    @staticmethod
    def _pick_bar_chars():
        try:
            '█░'.encode(sys.stdout.encoding or 'utf-8')
            return '█', '░'
        except Exception:
            return '#', '-'

    def start(self, serials):
        """진행률 표시를 시작합니다. 콘솔 로그는 화면이 섞이지 않도록 잠시 끕니다."""
        if not sys.stdout.isatty() or not _enable_ansi():
            return False  # 출력이 리다이렉트된 경우 기존 로그 방식 유지
        with self.lock:
            self.order = list(serials)
            self.states = {
                serial: {'index': 0, 'label': '대기 중', 'status': 'wait',
                         'model': '', 'series': '', 'note': '',
                         'started_at': None, 'finished_at': None}
                for serial in serials
            }
            self.started_at = time.time()
            self.active = True
        for handler in list(logging.getLogger().handlers):
            if isinstance(handler, logging.StreamHandler):
                self.console_handler = handler
                logging.getLogger().removeHandler(handler)
        self.stop_event.clear()
        self.render_thread = threading.Thread(target=self._render_loop, daemon=True)
        self.render_thread.start()
        return True

    def stop(self):
        """마지막 화면을 한 번 더 그리고 콘솔 로그를 되돌립니다."""
        if not self.active:
            return
        self.stop_event.set()
        if self.render_thread:
            self.render_thread.join(timeout=2)
        self._draw()
        self.active = False
        if self.console_handler:
            logging.getLogger().addHandler(self.console_handler)
            self.console_handler = None
        sys.stdout.write('\n')
        sys.stdout.flush()

    # --- 워커 스레드가 호출하는 보고용 메서드 ---

    def set_info(self, serial, model, series):
        self._update(serial, model=model, series=series)

    def step(self, serial, index, label):
        self._update(serial, index=index, label=label, status='run')

    def note(self, serial, message):
        """정상적으로 건너뛴 단계 등 참고 사항을 노란색으로 남깁니다."""
        self._update(serial, note=message)

    def done(self, serial):
        self._update(serial, index=self.total_steps, label='초기화 완료',
                     status='done', finished_at=time.time())

    def fail(self, serial, message):
        self._update(serial, label=message, status='fail', finished_at=time.time())

    def _update(self, serial, **fields):
        if not self.active:
            return
        with self.lock:
            state = self.states.get(serial)
            if state is None:
                return
            if state['started_at'] is None and fields.get('status') == 'run':
                state['started_at'] = time.time()
            state.update(fields)

    # --- 렌더 스레드 전용 ---

    def _render_loop(self):
        while not self.stop_event.is_set():
            self._draw()
            self.stop_event.wait(0.15)

    def _draw(self):
        try:
            columns = max(48, shutil.get_terminal_size((80, 25)).columns)
        except Exception:
            columns = 80
        bar_width = max(10, min(40, columns - 30))

        with self.lock:
            states = {serial: dict(self.states[serial]) for serial in self.order}
            order = list(self.order)
            elapsed = time.time() - self.started_at

        completed = sum(1 for s in states.values() if s['status'] == 'done')
        failed = sum(1 for s in states.values() if s['status'] == 'fail')
        all_settled = (completed + failed) == len(order) and order
        rule = '═' * min(68, columns - 2)
        lines = []

        lines.append(' ' + self.WHITE + '갤럭시 초기화 V8' + self.RESET +
                     self.DIM + '   기기 %d대' % len(order) + self.RESET)
        summary_color = self.GREEN if (all_settled and not failed) else self.WHITE
        summary = (self.DIM + ' 완료 ' + self.RESET + summary_color +
                   '%d / %d' % (completed, len(order)) + self.RESET)
        if failed:
            summary += self.DIM + '     실패 ' + self.RESET + self.RED + str(failed) + self.RESET
        summary += self.DIM + '     경과 ' + self.RESET + self.WHITE + self._mmss(elapsed) + self.RESET
        lines.append(summary)
        lines.append(self.DIM + rule + self.RESET)

        for position, serial in enumerate(order, start=1):
            state = states[serial]
            status = state['status']
            index = state['index']
            fraction = min(1.0, index / float(self.total_steps)) if self.total_steps else 0.0

            if status == 'done':
                head_color, bar_color = self.GREEN, self.GREEN
            elif status == 'fail':
                head_color, bar_color = self.RED, self.RED
            elif status == 'wait':
                head_color, bar_color = self.DIM, self.DIM
            else:
                head_color, bar_color = self.WHITE, self.CYAN

            model = (' ' + state['model']) if state['model'] else ''
            series = (' ' + state['series']) if state['series'] else ''
            lines.append(head_color + ' [%d] %s%s%s' % (position, serial, model, series) + self.RESET)

            filled = int(round(fraction * bar_width))
            bar = (self.DIM + '[' + self.RESET + bar_color + self.fill_char * filled + self.RESET +
                   self.DIM + self.empty_char * (bar_width - filled) + ']' + self.RESET)
            percent_color = self.GREEN if status == 'done' else (self.RED if status == 'fail' else self.WHITE)
            lines.append('     ' + bar + '  ' + percent_color + '%3d%%' % round(fraction * 100) + self.RESET +
                         self.DIM + '  %2d/%d' % (index, self.total_steps) + self.RESET)

            if status == 'done':
                spent = (state['finished_at'] or 0) - (state['started_at'] or state['finished_at'] or 0)
                lines.append(self.GREEN + '     √ 초기화 완료' + self.RESET +
                             self.DIM + '  (%s)' % self._mmss(spent) + self.RESET)
            elif status == 'fail':
                lines.append(self.RED + '     × ' + state['label'] + self.RESET)
            elif status == 'wait':
                lines.append(self.DIM + '     · 대기 중' + self.RESET)
            elif state['note']:
                lines.append(self.YELLOW + '     ! ' + state['note'] + self.RESET)
            else:
                spin = self.SPINNER[int(time.time() * 6) % len(self.SPINNER)]
                lines.append(self.CYAN + '     %s %s' % (spin, state['label']) + self.RESET)

        lines.append(self.DIM + rule + self.RESET)
        if all_settled and failed:
            lines.append(self.RED + ' 실패 %d대 — 해당 기기는 다시 초기화하세요.' % failed + self.RESET)
        elif all_settled:
            lines.append(self.GREEN + ' 모든 기기 초기화 완료.' + self.RESET)
        else:
            lines.append(self.DIM + ' 기기를 뽑지 마세요. 완료된 기기부터 분리할 수 있습니다.' + self.RESET)

        buffer = []
        if self.lines_drawn:
            buffer.append('\x1b[%dA' % self.lines_drawn)  # 이전에 그린 만큼 커서를 위로
        for line in lines:
            buffer.append('\x1b[2K' + self._clip(line, columns - 1) + '\n')
        sys.stdout.write(''.join(buffer))
        sys.stdout.flush()
        self.lines_drawn = len(lines)

    def _clip(self, line, limit):
        """ANSI 색 코드는 폭 계산에서 제외하고 자릅니다."""
        parts = re.split(r'(\x1b\[[0-9;]*m)', line)
        out, width = [], 0
        for part in parts:
            if part.startswith('\x1b['):
                out.append(part)
                continue
            trimmed = _trim_to_width(part, limit - width)
            out.append(trimmed)
            width += _display_width(trimmed)
        return ''.join(out) + self.RESET

    @staticmethod
    def _mmss(seconds):
        seconds = max(0, int(seconds))
        return '%02d:%02d' % (seconds // 60, seconds % 60)


PROGRESS = None  # main()에서 기기 수를 알 때 생성


def run_command(cmd, check=False, timeout=60, retries=1):
    """주어진 명령어를 실행하고 결과를 반환합니다. 실패 시 재시도합니다."""
    for attempt in range(1 + retries):
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, check=check, timeout=timeout, encoding='utf-8', errors='replace')
            # 보안 폴더(user 150) 접근 에러는 무시 — shell 권한으로 접근 불가
            if result.stderr and 'SecurityException' in result.stderr and 'user 150' in result.stderr:
                logging.debug('[보안폴더] 무시: %s', result.stderr.strip().split('\n')[0])
                result = subprocess.CompletedProcess(cmd, returncode=0, stdout=result.stdout, stderr='')
            if result.returncode == 0 or not check:
                return result
        except subprocess.TimeoutExpired:
            logging.warning('명령어 타임아웃(%ds): %s (시도 %d/%d)', timeout, ' '.join(cmd), attempt + 1, 1 + retries)
        except subprocess.CalledProcessError as e:
            logging.error('명령어 실행 실패: %s / %s (시도 %d/%d)', ' '.join(cmd), e.stderr, attempt + 1, 1 + retries)
            if attempt == retries:
                return e
        if attempt < retries:
            logging.info('RETRY: %s', ' '.join(cmd))
            time.sleep(2)
    # 타임아웃 등으로 result가 없는 경우 빈 결과 반환
    return subprocess.CompletedProcess(cmd, returncode=-1, stdout='', stderr='TIMEOUT')


def get_connected_devices():
    """연결된 ADB 디바이스 목록을 가져옵니다."""
    output = subprocess.check_output(['adb', 'devices']).decode('utf-8')
    devices = []
    for line in output.strip().splitlines()[1:]:
        parts = line.strip().split('\t')
        if len(parts) == 2 and parts[1] == 'device':
            devices.append(parts[0])
    return devices


MODEL_TO_SERIES = {
    'S91': 'S23', 'S92': 'S24', 'S93': 'S25', 'S94': 'S26',
}


def get_device_model(serial):
    """모델명(예: SM-S948N)을 반환합니다. 조회 실패 시 빈 문자열."""
    result = run_command(['adb', '-s', serial, 'shell', 'getprop', 'ro.product.model'])
    return result.stdout.strip() if hasattr(result, 'stdout') and result.stdout else ''


def detect_series(serial):
    """모델명(getprop)으로 디바이스 시리즈를 자동 감지합니다."""
    model = get_device_model(serial)
    if model:
        # SM-S948N → S94 → S26
        for prefix, series in MODEL_TO_SERIES.items():
            if prefix in model:
                logging.info('[%s] 모델: %s → 시리즈: %s', serial, model, series)
                return series
        logging.warning('[%s] 모델 %s — 매칭 없음, 기본값 S24 사용', serial, model)
    else:
        logging.warning('[%s] 모델명 조회 실패 — 기본값 S24 사용', serial)
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


def push_dex_if_needed(serial, dex_name):
    """DEX 헬퍼 파일을 기기에 푸시합니다. push 후 리모트 존재 여부를 검증합니다."""
    local_path = resource_path(dex_name)
    remote_path = f'/data/local/tmp/{dex_name}'
    if not os.path.exists(local_path):
        logging.warning('[%s] DEX 파일 없음: %s', serial, local_path)
        return False
    run_command(['adb', '-s', serial, 'push', local_path, remote_path])
    # 리모트 파일 존재 확인
    check = run_command(['adb', '-s', serial, 'shell', 'ls', remote_path])
    if hasattr(check, 'returncode') and check.returncode != 0:
        logging.warning('[%s] DEX push 검증 실패, 재시도: %s', serial, dex_name)
        run_command(['adb', '-s', serial, 'push', local_path, remote_path])
    return True


BASE_LOCALES = ['ko-KR', 'en-US', 'ja-JP']


def set_device_language(serial, locale):
    """app_process + DEX를 통해 기기 언어를 변경합니다 (비루트 호환).

    선택된 언어를 기본으로, 나머지 기본 언어(한국어/영어/일본어)를 보조로 설정합니다.
    예: ja-JP 선택 → ja-JP, ko-KR, en-US
    """
    if not locale:
        return
    # 선택된 언어를 맨 앞에, 나머지 기본 언어를 뒤에 배치
    locale_list = [locale] + [l for l in BASE_LOCALES if l != locale]
    logging.info('[%s] 언어 설정 변경: %s', serial, ', '.join(locale_list))

    # DEX 파일 푸시
    if not push_dex_if_needed(serial, 'locale_changer.dex'):
        logging.error('[%s] locale_changer.dex 없음 — 언어 변경 불가', serial)
        return

    # app_process로 LocaleChanger 실행 (여러 로케일을 인자로 전달)
    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/locale_changer.dex',
        'app_process', '/system/bin', 'LocaleChanger'
    ] + locale_list)

    if hasattr(result, 'stdout') and 'SUCCESS' in result.stdout:
        logging.info('[%s] 언어 설정 완료: %s', serial, ', '.join(locale_list))
    else:
        stderr = result.stderr if hasattr(result, 'stderr') else ''
        stdout = result.stdout if hasattr(result, 'stdout') else ''
        logging.warning('[%s] 언어 설정 결과 불확실: %s %s', serial, stdout.strip(), stderr.strip())


# ============================================================
# 2. 계정 관리: 삼성 계정 제외 모든 계정 삭제
# ============================================================

# 삼성 계정 타입 (보존)
SAMSUNG_ACCOUNT_TYPES = [
    'com.osp.app.signin',           # 삼성 계정 기본
    'com.samsung.android.mobileservice',  # 삼성 모바일 서비스
    'com.samsung',                   # 삼성 공통 prefix
]


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
    """삼성 계정을 제외한 모든 계정을 삭제합니다 (app_process + DEX 방식)."""
    logging.info('[%s] 삼성 계정 제외 전체 계정 삭제 시작...', serial)

    # 삭제 전 계정 현황 로깅
    accounts = get_device_accounts(serial)
    if not accounts:
        logging.info('[%s] 계정 조회 결과 없음 — DEX로 직접 삭제 시도', serial)
    else:
        for account in accounts:
            if is_samsung_account(account['type']):
                logging.info('[%s] 삼성 계정 보존 대상: %s (%s)', serial, account['name'], account['type'])
            else:
                logging.info('[%s] 계정 삭제 대상: %s (%s)', serial, account['name'], account['type'])

    # DEX 파일 푸시
    if not push_dex_if_needed(serial, 'account_remover.dex'):
        logging.error('[%s] account_remover.dex 없음 — 계정 삭제 불가', serial)
        return

    # app_process로 AccountRemover 실행
    # (IAccountManager.removeAccountAsUser를 직접 호출하여 계정 삭제)
    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/account_remover.dex',
        'app_process', '/system/bin', 'AccountRemover'
    ])

    stdout = result.stdout if hasattr(result, 'stdout') else ''

    # 결과 파싱
    for line in stdout.splitlines():
        line = line.strip()
        if line.startswith('REMOVING:'):
            parts = line.split(':', 2)
            if len(parts) >= 3:
                logging.info('[%s] 계정 삭제 시도: %s (%s)', serial, parts[1], parts[2])
        elif line.startswith('OK:'):
            logging.info('[%s] 계정 삭제 요청 완료: %s', serial, line[3:])
        elif line.startswith('FAIL:'):
            logging.warning('[%s] 계정 삭제 실패: %s', serial, line[5:])
        elif line.startswith('REMAINING:'):
            logging.info('[%s] 남은 계정 수: %s', serial, line[10:])
        elif line.startswith('ACCOUNT:'):
            parts = line.split(':', 2)
            if len(parts) >= 3:
                logging.info('[%s]   - %s (%s)', serial, parts[1], parts[2])

    # 삭제 후 확인
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
        # 삼성 OneUI 시스템 휴지통 (실제 삭제 파일이 보관되는 경로)
        '/storage/emulated/0/Android/.Trash',
        # 기타 시스템 휴지통
        '/sdcard/.Trash',
        '/sdcard/.Trash-0',
        '/sdcard/Recycle',
        # 썸네일 캐시 (갤러리가 이걸 참조하여 삭제된 이미지 표시)
        '/sdcard/DCIM/.thumbnails',
        '/sdcard/Pictures/.thumbnails',
        '/sdcard/Music/.thumbnails',
        '/sdcard/Movies/.thumbnails',
        '/sdcard/Download/.thumbnails',
        '/sdcard/Android/data/com.sec.android.gallery3d/files/.thumbnails',
        # 추가 캐시 경로
        '/sdcard/Android/data/com.android.chrome/cache',
        '/sdcard/Android/data/dji.mimo/cache',
        '/sdcard/Android/data/com.nhn.android.nmap/cache',
    ]

    clear_packages = [
        'com.sec.android.gallery3d',
        'com.sec.android.app.myfiles',
        'com.samsung.android.providers.media',
        'com.samsung.android.providers.trash',
        'com.android.providers.media',
        'com.google.android.providers.media.module',
    ]

    # Step 1: 갤러리/파일 앱 강제 종료
    force_cmd = 'am force-stop com.sec.android.gallery3d; am force-stop com.sec.android.app.myfiles'
    run_command(['adb', '-s', serial, 'shell', force_cmd])

    # Step 2: 휴지통/캐시 물리 파일 삭제
    rm_cmd = 'rm -rf ' + ' '.join(trash_paths)
    result = run_command(['adb', '-s', serial, 'shell', rm_cmd])
    if hasattr(result, 'returncode') and result.returncode != 0:
        logging.warning('[%s] rm -rf 일부 실패 (계속 진행)', serial)

    # Step 3: 미디어/갤러리 프로바이더 데이터 초기화
    pm_cmd = '; '.join(f'pm clear {pkg}' for pkg in clear_packages)
    result = run_command(['adb', '-s', serial, 'shell', pm_cmd], timeout=90)
    stdout = result.stdout if hasattr(result, 'stdout') else ''
    for line in stdout.splitlines():
        if 'Exception' in line or 'Error' in line:
            logging.warning('[%s] pm clear 경고: %s', serial, line.strip())

    # MediaStore 전체 리프레시 (pm clear 후 provider 재기동 트리거)
    # Android 16에서 MEDIA_MOUNTED는 SecurityException 발생 → 폴백으로 MediaProvider 재시작
    result = run_command([
        'adb', '-s', serial, 'shell',
        'am', 'broadcast', '-a', 'android.intent.action.MEDIA_MOUNTED',
        '-d', 'file:///sdcard'
    ])
    stderr = result.stderr if hasattr(result, 'stderr') else ''
    if 'SecurityException' in stderr:
        logging.info('[%s] MEDIA_MOUNTED 권한 거부 — MediaProvider 재시작으로 폴백', serial)
        run_command(['adb', '-s', serial, 'shell',
                     'am', 'force-stop', 'com.android.providers.media'])
        run_command(['adb', '-s', serial, 'shell',
                     'am', 'force-stop', 'com.google.android.providers.media.module'])
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
        'com.alphainventor.filemanager',
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
        'com.sec.android.app.sbrowser': '삼성 인터넷 브라우저',
    }
    logging.info('[%s] Google 앱 사용 기록 삭제 시작...', serial)
    for package, name in google_apps.items():
        clear_app_data(serial, package, name)
    logging.info('[%s] Google 앱 사용 기록 삭제 완료.', serial)


def reset_camera_settings(serial):
    """카메라 앱 데이터를 초기화하여 설정(화면비, 워터마크, 격자 등)을 기본값으로 되돌립니다."""
    camera_apps = {
        'com.sec.android.app.camera': '카메라',
        'com.samsung.android.app.cameraassistant': '카메라 어시스턴트',
    }
    logging.info('[%s] 카메라 설정 초기화 시작...', serial)
    for package, name in camera_apps.items():
        clear_app_data(serial, package, name)
    logging.info('[%s] 카메라 설정 초기화 완료.', serial)


def reset_navigation_bar(serial):
    """내비게이션 바를 공장 기본값(3버튼, 최근앱-홈-뒤로가기 순서)으로 되돌립니다.

    One UI는 내비게이션 모드를 RRO 오버레이로, 세부 옵션(버튼 순서 등)을
    settings global/secure로 관리하므로 두 층을 모두 초기화해야 합니다.
    공장 기본 상태 = 모든 navbar 오버레이 비활성 + 아래 settings 값 (S26 실측).
    """
    navbar_overlays = [
        'com.samsung.internal.systemui.navbar.sec_gestural',
        'com.samsung.internal.systemui.navbar.sec_gestural_no_hint',
        'com.samsung.internal.systemui.navbar.gestural_no_hint',
        'com.android.internal.systemui.navbar.gestural',
        'com.android.internal.systemui.navbar.transparent',
        'com.android.internal.systemui.navbar.threebutton',
    ]
    default_settings = [
        ('global', 'navigation_bar_gesture_while_hidden', '0'),  # 제스처 → 버튼
        ('secure', 'navigation_mode', '0'),                      # 3버튼 모드
        ('global', 'navigationbar_key_order', '0'),              # 최근앱-홈-뒤로가기
        ('secure', 'navigationbar_key_order', '0'),
        ('global', 'navigation_bar_gesture_hint', '1'),          # 제스처 힌트 기본 on
    ]
    logging.info('[%s] 내비게이션 바 초기화 시작...', serial)
    for overlay in navbar_overlays:
        run_command(['adb', '-s', serial, 'shell',
                     'cmd', 'overlay', 'disable', '--user', '0', overlay])
    for namespace, key, value in default_settings:
        run_command(['adb', '-s', serial, 'shell',
                     'settings', 'put', namespace, key, value])
    logging.info('[%s] 내비게이션 바 초기화 완료.', serial)


def reset_font_settings(serial):
    """글자 크기/굵기/글자체를 공장 기본값으로 되돌립니다.

    system font_scale이 실제 렌더링을 결정하고(시스템 Configuration에 즉시 반영),
    global font_size는 삼성 설정 UI의 슬라이더 위치(0~6, 기본 2)를 저장합니다.
    두 값이 어긋나면 UI 슬라이더와 실제 크기가 따로 놀기 때문에 함께 맞춥니다.
    """
    default_settings = [
        ('system', 'font_scale', '1.0'),          # 실제 렌더링 배율
        ('system', 'device_font_scale', '1.0'),
        ('global', 'font_size', '2'),             # 설정 UI 슬라이더 기본 위치
        ('global', 'bold_text', '0'),             # 굵게 표시 해제
        ('global', 'font_style_index', '0'),      # 기본 글자체
        ('secure', 'enhanced_comfort_font_value', '0'),
    ]
    logging.info('[%s] 글자 크기/글자체 초기화 시작...', serial)
    for namespace, key, value in default_settings:
        run_command(['adb', '-s', serial, 'shell',
                     'settings', 'put', namespace, key, value])
    logging.info('[%s] 글자 크기/글자체 초기화 완료.', serial)


def clear_call_sms_contacts(serial):
    """통화기록, SMS, 주소록을 DEX(ContentCleaner)를 통해 삭제합니다."""
    logging.info('[%s] 통화기록/SMS/주소록 DEX 삭제 시작...', serial)

    if not push_dex_if_needed(serial, 'content_cleaner.dex'):
        logging.warning('[%s] content_cleaner.dex 없음 — 폴백: pm clear 사용', serial)
        clear_app_data(serial, 'com.android.providers.contacts', '주소록/통화기록')
        clear_app_data(serial, 'com.android.providers.telephony', 'SMS/MMS')
        return

    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/content_cleaner.dex',
        'app_process', '/system/bin', 'ContentCleaner'
    ])

    stdout = result.stdout if hasattr(result, 'stdout') else ''
    needs_fallback = {'CALL_LOG': False, 'SMS': False, 'CONTACTS': False}

    for line in stdout.splitlines():
        line = line.strip()
        if line.startswith('OK:'):
            parts = line.split(':', 3)
            if len(parts) >= 3:
                logging.info('[%s] %s 삭제 완료: %s', serial, parts[1], parts[2])
        elif line.startswith('PERMISSION_DENIED:') or line.startswith('FALLBACK:'):
            parts = line.split(':', 3)
            if len(parts) >= 2:
                label = parts[1]
                logging.warning('[%s] %s 권한 부족 — pm clear 폴백', serial, label)
                needs_fallback[label] = True
        elif line.startswith('FAIL:'):
            parts = line.split(':', 3)
            if len(parts) >= 2:
                logging.warning('[%s] %s 삭제 실패: %s', serial, parts[1],
                              parts[2] if len(parts) > 2 else '')

    # 실패한 항목에 대해 pm clear 폴백
    if needs_fallback.get('CALL_LOG') or needs_fallback.get('CONTACTS'):
        clear_app_data(serial, 'com.android.providers.contacts', '주소록/통화기록 (폴백)')
    if needs_fallback.get('SMS'):
        clear_app_data(serial, 'com.android.providers.telephony', 'SMS/MMS (폴백)')

    # 삼성 메시지 앱 데이터 초기화 (임시저장 문자 등 앱 자체 DB 삭제)
    clear_app_data(serial, 'com.samsung.android.messaging', '삼성 메시지 (임시저장 포함)')
    clear_app_data(serial, 'com.samsung.android.providers.contacts', '삼성 연락처 프로바이더')
    clear_app_data(serial, 'com.samsung.android.app.contacts', '삼성 연락처 앱')
    clear_app_data(serial, 'com.samsung.android.dsms', '삼성 DSMS')
    clear_app_data(serial, 'com.android.mms.service', 'MMS 서비스')

    logging.info('[%s] 통화기록/SMS/주소록 삭제 완료', serial)


def delete_esim_profiles(serial):
    """e-SIM 프로필을 감지하고 비활성화 + 삭제합니다."""
    logging.info('[%s] e-SIM 프로필 확인 시작...', serial)

    if not push_dex_if_needed(serial, 'esim_manager.dex'):
        logging.warning('[%s] esim_manager.dex 없음 — e-SIM 처리 건너뜀', serial)
        return

    # DEX로 e-SIM 프로필 비활성화 + 삭제
    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/esim_manager.dex',
        'app_process', '/system/bin', 'EsimManager', 'delete-all'
    ])

    stdout = result.stdout if hasattr(result, 'stdout') else ''
    stderr = result.stderr if hasattr(result, 'stderr') else ''
    esim_found = False
    all_deleted = False
    all_disabled = False
    delete_failed = False

    for line in stdout.splitlines():
        line = line.strip()
        if line.startswith('ESIM:'):
            esim_found = True
            parts = line.split(':', 6)
            if len(parts) >= 4:
                logging.info('[%s] e-SIM 발견: subId=%s iccId=%s 캐리어=%s',
                            serial, parts[1], parts[2], parts[3])
        elif line.startswith('SUCCESS:NO_ESIM'):
            logging.info('[%s] e-SIM 프로필 없음', serial)
            return
        elif line.startswith('OK:ESIM_DISABLED'):
            logging.info('[%s] e-SIM 비활성화 완료: %s', serial, line.split(':', 2)[-1])
        elif line.startswith('SUCCESS:ALL_ESIM_DISABLED'):
            all_disabled = True
            logging.info('[%s] 모든 e-SIM 프로필 비활성화 완료', serial)
        elif line.startswith('OK:ESIM_DELETED'):
            logging.info('[%s] e-SIM 삭제 완료: %s', serial, line.split(':', 2)[-1])
        elif line.startswith('SUCCESS:ALL_ESIM_DELETED'):
            all_deleted = True
            logging.info('[%s] 모든 e-SIM 프로필 삭제 완료', serial)
        elif line.startswith('FAIL:ESIM_DELETE'):
            delete_failed = True
            logging.warning('[%s] e-SIM 삭제 실패: %s', serial, line.split(':', 2)[-1])
        elif line.startswith('FAIL:ESIM_DISABLE'):
            logging.warning('[%s] e-SIM 비활성화 실패: %s', serial, line.split(':', 2)[-1])
        elif line.startswith('FAIL:ALL_ESIM_DELETE_FAILED'):
            delete_failed = True
            logging.warning('[%s] 모든 e-SIM 삭제 실패', serial)
        elif line.startswith('DISABLE_METHOD:') or line.startswith('DELETE_METHOD:'):
            logging.info('[%s] %s', serial, line)

    if stderr:
        for line in stderr.splitlines():
            line = line.strip()
            if line:
                logging.warning('[%s] [stderr] %s', serial, line)

    # 삭제 실패시에만 수동 안내
    if esim_found and delete_failed and not all_deleted:
        run_command([
            'adb', '-s', serial, 'shell',
            'am', 'start', '-a', 'android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS'
        ])
        print()
        print('=' * 60)
        print('  ⚠  e-SIM 프로필 자동 삭제에 실패했습니다!')
        print('  기기 화면에서 수동으로 삭제해주세요.')
        print()
        print('  👉 SIM 관리자 > eSIM 선택 > 삭제')
        print('=' * 60)
        print()
        logging.warning('[%s] ⚠ e-SIM 수동 삭제 필요 — SIM 관리자 화면을 열었습니다.', serial)

    logging.info('[%s] e-SIM 프로필 처리 완료', serial)


def clear_logs_and_cache(serial):
    """로그, 통화기록, 주소록, 앱 캐시를 삭제합니다."""
    # DEX 기반 통화기록/SMS/주소록 삭제
    clear_call_sms_contacts(serial)

    clear_app_data(serial, 'com.sec.android.gallery3d', '갤러리')
    clear_app_data(serial, 'com.nhn.android.nmap', 'Nmap')
    clear_app_data(serial, 'com.sec.android.themestore', '테마')
    clear_app_data(serial, 'com.sec.android.app.vepreload', '삼성 스튜디오')

    # 클립보드 기록 제거 (엣지 패널 + 키보드 내 클립보드)
    clear_app_data(serial, 'com.samsung.android.app.clipboardedge', '클립보드 엣지')
    clear_app_data(serial, 'com.samsung.android.honeyboard', '삼성 키보드 (클립보드 포함)')


def clear_recent_tasks(serial):
    """최근 앱 목록을 제거합니다 (app_process + DEX 방식)."""
    logging.info('[%s] 최근 앱 목록 제거 중...', serial)
    if not push_dex_if_needed(serial, 'recent_tasks_cleaner.dex'):
        logging.error('[%s] recent_tasks_cleaner.dex 없음 — 최근 앱 제거 불가', serial)
        return

    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/recent_tasks_cleaner.dex',
        'app_process', '/system/bin', 'RecentTasksCleaner'
    ])

    stdout = result.stdout if hasattr(result, 'stdout') else ''
    if 'SUCCESS' in stdout:
        logging.info('[%s] %s', serial, stdout.strip())
    else:
        stderr = result.stderr if hasattr(result, 'stderr') else ''
        logging.warning('[%s] 최근 앱 제거 결과 불확실: %s %s', serial, stdout.strip(), stderr.strip())


def clear_media_store(serial):
    """MediaStore DB를 정리합니다 (이미지, 비디오, 오디오, 파일 전체)."""
    logging.info('[%s] MediaStore DB 정리 중...', serial)
    media_uris = [
        'content://media/external/images/media',
        'content://media/external/video/media',
        'content://media/external/audio/media',
        'content://media/external/file',
    ]
    for uri in media_uris:
        run_command([
            'adb', '-s', serial, 'shell',
            'content', 'delete', '--uri', uri
        ])
    logging.info('[%s] MediaStore DB 정리 완료', serial)


def push_default_wallpaper(serial, wallpaper_file, series=None):
    """기본 배경화면을 기기에 푸시하고 홈/잠금화면으로 설정합니다."""
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
    logging.info('[%s] 배경화면 파일 푸시 완료', serial)

    # 삼성 라이브 배경화면 오버레이 앱 강제 종료 + 초기화
    # (dressroom이 라이브 배경을 자동 복원하므로 force-stop → pm clear 순서 필수)
    overlay_pkgs = ['com.samsung.android.wallpaper.live',
                    'com.samsung.android.dynamiclock',
                    'com.samsung.android.app.dressroom']
    for pkg in overlay_pkgs:
        run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', pkg])
    for pkg in overlay_pkgs:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', pkg])
    time.sleep(3)

    # DEX로 홈화면 + 잠금화면 자동 설정
    if not push_dex_if_needed(serial, 'wallpaper_setter.dex'):
        logging.warning('[%s] wallpaper_setter.dex 없음 — 배경화면 자동 설정 건너뜀', serial)
        return

    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/wallpaper_setter.dex',
        'app_process', '/system/bin', 'WallpaperSetter', remote_path
    ])

    stdout = result.stdout if hasattr(result, 'stdout') else ''
    if 'FAIL' in stdout:
        logging.warning('[%s] 배경화면 설정 실패 — 오버레이 재정리 후 재시도: %s', serial, stdout.strip())
        for pkg in overlay_pkgs:
            run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', pkg])
            run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', pkg])
        time.sleep(3)
        result = run_command([
            'adb', '-s', serial, 'shell',
            'CLASSPATH=/data/local/tmp/wallpaper_setter.dex',
            'app_process', '/system/bin', 'WallpaperSetter', remote_path
        ])
        stdout = result.stdout if hasattr(result, 'stdout') else ''

    if 'SUCCESS' in stdout:
        logging.info('[%s] 홈화면 + 잠금화면 배경 설정 완료', serial)
    else:
        stderr = result.stderr if hasattr(result, 'stderr') else ''
        logging.warning('[%s] 배경화면 설정 결과 불확실: %s %s', serial, stdout.strip(), stderr.strip())

    # 설정 후 검증: 라이브 배경이 다시 덮어씌웠는지 확인 (5초 대기 후 체크)
    time.sleep(5)
    verify = run_command(['adb', '-s', serial, 'shell', 'dumpsys', 'wallpaper'])
    verify_out = verify.stdout if hasattr(verify, 'stdout') else ''

    # 홈화면 또는 잠금화면에 라이브 배경 컴포넌트가 있으면 재설정
    live_override = ('InfinityWallpaper' in verify_out or 'wallpaper.live' in verify_out) \
        and 'mName=WallpaperSetter' not in verify_out
    if live_override:
        logging.warning('[%s] 라이브 배경 오버레이 감지 — force-stop + pm clear 후 재설정', serial)
        for pkg in overlay_pkgs:
            run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', pkg])
            run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', pkg])
        time.sleep(5)
        result = run_command([
            'adb', '-s', serial, 'shell',
            'CLASSPATH=/data/local/tmp/wallpaper_setter.dex',
            'app_process', '/system/bin', 'WallpaperSetter', remote_path
        ])
        stdout2 = result.stdout if hasattr(result, 'stdout') else ''
        if 'SUCCESS' in stdout2:
            logging.info('[%s] 배경화면 재설정 성공 (오버레이 제거 후)', serial)
        else:
            logging.warning('[%s] 배경화면 재설정 실패: %s', serial, stdout2.strip())



def wipe_internal_storage(serial):
    """내장 메모리 전체를 삭제합니다."""
    logging.info('[%s] 내장 메모리 전체 삭제 시작', serial)
    run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', '/storage/emulated/0/*'])
    run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', '/storage/emulated/0/.*'])
    logging.info('[%s] 내장 메모리 전체 삭제 완료', serial)


def ensure_hotseat_apps(serial):
    """Hot seat(하단 도크)에 배치될 핵심 앱이 활성화되어 있는지 보장합니다.

    이 앱들이 disabled / uninstall-for-user 상태면 삼성 기본 레이아웃이 Hot seat
    슬롯을 빈 칸으로 처리합니다. launcher clear 직전에 복원하여 기본 레이아웃이
    정상적으로 Hot seat에 배치되도록 합니다.

    - install-existing: 시스템 파티션 APK를 현재 user에 재연결 (누락 시 복원)
    - pm enable: disabled 상태면 활성화
    두 명령 모두 idempotent이므로 조건 없이 실행합니다.
    """
    hotseat_apps = [
        ('com.samsung.android.dialer', '전화'),
        ('com.samsung.android.messaging', '메시지'),
        ('com.sec.android.app.sbrowser', '삼성 인터넷'),
        ('com.sec.android.app.camera', '카메라'),
    ]
    for pkg, name in hotseat_apps:
        # install-existing은 시스템 파티션에 APK가 있을 때만 성공.
        # APK 자체가 삭제된 경우(이전 사용자가 root/adb로 완전 제거)엔 NameNotFoundException 발생.
        result = run_command(['adb', '-s', serial, 'shell', 'cmd', 'package', 'install-existing', pkg])
        stdout = result.stdout if hasattr(result, 'stdout') and result.stdout else ''
        stderr = result.stderr if hasattr(result, 'stderr') and result.stderr else ''
        if 'NameNotFoundException' in stdout or 'NameNotFoundException' in stderr or "doesn't exist" in stdout + stderr:
            logging.warning('[%s] Hot seat 앱 누락(시스템 APK 없음): %s — 다음 사용자에게 placeholder 노출됨', serial, name)
            continue
        run_command(['adb', '-s', serial, 'shell', 'pm', 'enable', pkg])
        logging.info('[%s] Hot seat 앱 보장: %s (%s)', serial, name, pkg)


def reset_widgets_only(serial):
    """launcher.db 보존하면서 위젯만 제거.

    pm clear launcher를 하지 않으므로 default layout 적용 X → placeholder 생성 trigger 없음.
    위젯 provider를 일시 disable → launcher force-stop + start 시 binding fail →
    launcher.db의 위젯 row가 빈 슬롯으로 저장됨. 이후 provider re-enable해도 재등장 X.

    이전 사용자의 홈 아이콘 배치/폴더는 그대로 유지됨.
    (이전 사용자가 기기를 크게 커스터마이징하지 않았다면 실질적으로 깔끔한 결과)
    """
    logging.info('[%s] 위젯 전용 제거 — launcher.db 보존', serial)
    result = run_command(['adb', '-s', serial, 'shell', 'dumpsys', 'appwidget'])
    dump = result.stdout if hasattr(result, 'stdout') and result.stdout else ''

    providers = set()
    in_widgets_section = False
    for line in dump.split('\n'):
        stripped = line.strip()
        if stripped.startswith('Widgets:'):
            in_widgets_section = True
            continue
        if stripped.startswith('Hosts:') or stripped.startswith('Grants:'):
            in_widgets_section = False
            continue
        if in_widgets_section and 'provider=ProviderId' in line:
            match = re.search(r'cmp:ComponentInfo\{([^/]+)/', line)
            if match:
                providers.add(match.group(1))

    if not providers:
        logging.info('[%s] 바인딩된 위젯 없음 — 스킵', serial)
        return

    logging.info('[%s] 탐지된 위젯 provider (%d개): %s',
                 serial, len(providers), ', '.join(sorted(providers)))

    for pkg in providers:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'disable-user', '--user', '0', pkg])

    # launcher clear 없이 재시작 → db row 검증 + 빈 슬롯 저장
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.app.launcher'])
    time.sleep(1)
    run_command(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                 'com.sec.android.app.launcher/.activities.LauncherActivity'])
    time.sleep(4)

    for pkg in providers:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'enable', pkg])

    logging.info('[%s] 위젯 제거 완료 — provider 재활성화됨', serial)


def block_galaxy_store_auto_restore(serial):
    """Samsung 계정 보존하면서 Galaxy Store 자동 복원으로 인한 placeholder 차단.

    Samsung Cloud가 이전 기기의 설치 앱 목록을 복원 시도하면서
    app drawer에 회색 '설치 중' placeholder가 생성됨. Galaxy Store + Samsung Cloud
    데이터를 정리하고 일시 disable → launcher 재시작 → 남은 row 정리 → re-enable
    순서로 자동 복원 큐 생성을 차단.

    제한: Samsung 폴더 내부 placeholder는 launcher default layout XML 참조라
    본 함수로 해결되지 않음 (root 필요).
    """
    logging.info('[%s] Galaxy Store 자동 복원 차단 시작', serial)
    # Galaxy Store + Samsung Cloud 로컬 데이터/큐 삭제
    run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', 'com.sec.android.app.samsungapps'])
    run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', 'com.samsung.android.scloud'])
    # 일시 disable (자동 복원 trigger 차단)
    for pkg in ['com.sec.android.app.samsungapps', 'com.samsung.android.scloud']:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'disable-user', '--user', '0', pkg])
    # launcher 재시작 - disabled 상태에서 db row 검증 trigger
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.app.launcher'])
    time.sleep(1)
    run_command(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                 'com.sec.android.app.launcher/.activities.LauncherActivity'])
    time.sleep(5)
    # Galaxy Store/Cloud 재활성화 (기능 복원, launcher.db는 이미 정리됨)
    for pkg in ['com.sec.android.app.samsungapps', 'com.samsung.android.scloud']:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'enable', pkg])
    logging.info('[%s] Galaxy Store 자동 복원 차단 완료', serial)


def reset_launcher_with_clean_home(serial):
    """런처 초기화 + 홈화면에 바인딩된 모든 위젯 제거.

    2-pass 전략으로 OEM/통신사 변종을 모두 처리:
    1차: launcher clear → 삼성 기본 레이아웃 적용 → dumpsys로 바인딩된 위젯 provider 탐지
    2차: 탐지된 provider들을 일시 disable → launcher clear → 위젯 슬롯 빈 칸으로 저장
    최종: provider 재활성화 (앱 기능 복원, 이미 저장된 레이아웃은 변경되지 않음)

    하드코딩된 provider 리스트를 쓰지 않으므로 KT/SKT/LGU+, Galaxy S23~S26 등
    모든 변종에서 자동으로 작동합니다.
    """
    logging.info('[%s] 런처 초기화 1차 — 기본 레이아웃 적용 및 위젯 탐지', serial)
    run_command(['adb', '-s', serial, 'shell', 'pm', 'clear', 'com.sec.android.app.launcher'])
    time.sleep(1)  # 런처 프로세스 종료 + 재시작 대기
    # Samsung 런처는 activity가 실제로 표시될 때 default layout을 적용하고 위젯을 바인딩.
    # pm clear 직후엔 layout이 메모리에 없으므로, am start로 launcher activity를 강제 기동해야 함.
    run_command(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                 'com.sec.android.app.launcher/.activities.LauncherActivity'])
    time.sleep(4)  # default layout 적용 + 위젯 바인딩 완료 대기

    # 런처 호스트에 바인딩된 위젯의 provider 패키지 추출
    result = run_command(['adb', '-s', serial, 'shell', 'dumpsys', 'appwidget'])
    dump = result.stdout if hasattr(result, 'stdout') and result.stdout else ''

    providers = set()
    in_widgets_section = False
    for line in dump.split('\n'):
        stripped = line.strip()
        if stripped.startswith('Widgets:'):
            in_widgets_section = True
            continue
        if stripped.startswith('Hosts:') or stripped.startswith('Grants:'):
            in_widgets_section = False
            continue
        if in_widgets_section and 'provider=ProviderId' in line:
            match = re.search(r'cmp:ComponentInfo\{([^/]+)/', line)
            if match:
                providers.add(match.group(1))

    if not providers:
        logging.info('[%s] 바인딩된 위젯 없음 — 2차 clear 생략', serial)
        return

    logging.info('[%s] 탐지된 위젯 provider (%d개): %s',
                 serial, len(providers), ', '.join(sorted(providers)))

    # Provider 일시 disable
    for pkg in providers:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'disable-user', '--user', '0', pkg])

    # 위젯 + placeholder 제거: launcher.db 보존하면서 launcher만 재시작.
    # force-stop + start 시점에 launcher가 db row의 패키지 존재성 재검증 →
    # 누락된 위젯과 app drawer placeholder를 자동 정리.
    # (pm clear는 launcher.db를 새로 만들어 default layout 다시 적용 → placeholder 재발생)
    #
    # 모델별로 launcher의 자동 sweep 타이밍이 다름. 일부 모델(S24 등)은
    # background sweep job 완료까지 8초 이상 필요. KEYCODE_HOME으로 활성화도 trigger.
    logging.info('[%s] 런처 재시작 — 위젯 + placeholder 자동 정리', serial)
    run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.app.launcher'])
    time.sleep(1)
    run_command(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                 'com.sec.android.app.launcher/.activities.LauncherActivity'])
    time.sleep(4)
    # launcher가 화면에 노출되어야 background sweep이 trigger되는 모델 대응
    run_command(['adb', '-s', serial, 'shell', 'input', 'keyevent', 'KEYCODE_HOME'])
    time.sleep(8)  # background sweep job 완료 대기 (모델별로 4-10초 필요)

    # Provider 재활성화 (앱 기능 복원)
    for pkg in providers:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'enable', pkg])

    logging.info('[%s] 런처 초기화 완료 — 위젯 제거됨, provider 재활성화됨', serial)


def ensure_essential_apps_installed(serial):
    """필수 앱이 설치되어 있는지 확인하고 없으면 설치합니다."""
    apps = [
        {'package': 'com.nhn.android.nmap', 'name': 'Nmap', 'apk_path': resource_path('nmap.apk')},
        {'package': 'com.alphainventor.filemanager', 'name': 'File Manager', 'apk_path': resource_path('filemanager.apk')},
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

def build_pipeline(serial, locale, wallpaper, series):
    """초기화 단계를 (표시 이름, 실행 함수) 순서대로 정의합니다.

    순서에 의미가 있는 구간이 있으므로 아래 주석을 지키세요.
    - 카메라 초기화는 ensure_hotseat_apps 이전 (clear 후 pm enable로 마무리)
    - 런처 초기화는 배경화면 설정 직전 (clear 직후 런처가 배경 캐시를 재생성)
    """
    def clear_thumbnails():
        # MediaStore 리프레시 후 썸네일이 되살아나는 것을 막는 마무리 단계
        for folder in ['DCIM', 'Pictures', 'Music', 'Movies', 'Download']:
            run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', f'/sdcard/{folder}/.thumbnails'])

    return [
        ('언어 설정', lambda: set_device_language(serial, locale)),
        ('계정 삭제', lambda: remove_non_samsung_accounts(serial)),
        ('e-SIM 프로필 삭제', lambda: delete_esim_profiles(serial)),
        ('Google 앱 기록 삭제', lambda: clear_google_apps_history(serial)),
        ('사용자 설치 앱 삭제', lambda: delete_user_installed_apps(serial)),
        ('내부 저장소 초기화', lambda: wipe_internal_storage(serial)),
        ('로그·캐시 정리', lambda: clear_logs_and_cache(serial)),
        ('갤러리 휴지통 정리', lambda: deep_clean_gallery_trash(serial)),
        ('미디어 스토어 갱신', lambda: clear_media_store(serial)),
        ('썸네일 잔여물 제거', clear_thumbnails),
        ('카메라 설정 초기화', lambda: reset_camera_settings(serial)),
        ('내비게이션 바 초기화', lambda: reset_navigation_bar(serial)),
        ('글자 크기 초기화', lambda: reset_font_settings(serial)),
        ('홈 독 앱 배치', lambda: ensure_hotseat_apps(serial)),
        ('런처 초기화', lambda: reset_launcher_with_clean_home(serial)),
        ('Galaxy Store 복원 차단', lambda: block_galaxy_store_auto_restore(serial)),
        ('배경화면 적용', lambda: push_default_wallpaper(serial, wallpaper, series)),
        ('필수 앱 설치', lambda: ensure_essential_apps_installed(serial)),
        ('최근 앱 목록 제거', lambda: clear_recent_tasks(serial)),
    ]


# 단계를 추가/삭제해도 진행률 분모가 자동으로 따라가도록 실제 목록에서 센다
PIPELINE_STEP_COUNT = len(build_pipeline('', None, '', ''))


def process_device(serial, locale=None):
    """단일 기기에 대한 전체 초기화 프로세스를 실행합니다."""
    logging.info('========================================')
    logging.info('[%s] 초기화 시작', serial)
    logging.info('========================================')

    series = detect_series(serial)
    wallpaper = f'{series}.png'
    model = get_device_model(serial)
    if PROGRESS:
        PROGRESS.set_info(serial, model, series)

    steps = build_pipeline(serial, locale, wallpaper, series)
    for number, (label, action) in enumerate(steps, start=1):
        if PROGRESS:
            PROGRESS.step(serial, number - 1, label)
        logging.info('[%s] (%d/%d) %s', serial, number, len(steps), label)
        try:
            action()
        except Exception as e:
            # 기존 동작 유지 — 예외는 main()이 기기별로 잡아 기록하고 해당 기기만 중단
            if PROGRESS:
                PROGRESS.fail(serial, '%s 중 실패: %s' % (label, e))
            raise
        if PROGRESS:
            PROGRESS.step(serial, number, label)

    if PROGRESS:
        PROGRESS.done(serial)
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

            global PROGRESS
            PROGRESS = ProgressConsole(PIPELINE_STEP_COUNT)
            if not PROGRESS.start(devices):
                PROGRESS = None  # 콘솔이 아니면 기존 로그 방식으로 진행

            failures = []
            max_workers = min(5, len(devices))
            try:
                with ThreadPoolExecutor(max_workers=max_workers) as executor:
                    futures = {executor.submit(process_device, serial, locale): serial for serial in devices}
                    for future in as_completed(futures):
                        serial = futures[future]
                        try:
                            future.result()
                        except Exception as e:
                            failures.append(serial)
                            logging.error('[%s] 초기화 중 예외 발생: %s', serial, e)
            finally:
                if PROGRESS:
                    PROGRESS.stop()
                    PROGRESS = None

            logging.info('모든 기기 초기화 작업이 완료되었습니다.')
            if failures:
                logging.error('실패한 기기 %d대: %s — 다시 초기화하세요.',
                              len(failures), ', '.join(failures))

            # 기기 종료 여부 확인
            shutdown = input('기기를 종료하시겠습니까? (y/n): ').strip().lower()
            if shutdown == 'y':
                for serial in devices:
                    logging.info('[%s] 기기 종료 중...', serial)
                    run_command(['adb', '-s', serial, 'shell', 'reboot', '-p'])
                logging.info('모든 기기 종료 명령 전송 완료')

        try:
            logging.info('추가로 작업하실 기기를 연결 완료 후 엔터를 눌러주세요. (종료하려면 Ctrl+C)')
            input()
        except KeyboardInterrupt:
            logging.info('프로그램을 종료합니다.')
            break


if __name__ == '__main__':
    main()
