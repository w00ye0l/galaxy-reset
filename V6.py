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
import collections
import argparse
import hashlib
import shlex
import zipfile
import phone_autostart
import naver_account_cleanup
from concurrent.futures import ThreadPoolExecutor, wait as futures_wait

# Frozen Windows executables ignore PYTHONIOENCODING. Set streams before logging
# and argparse, including redirected output and the non-interactive help path.
if os.name == 'nt':
    for _console_stream in (sys.stdout, sys.stderr):
        if hasattr(_console_stream, 'reconfigure'):
            _console_stream.reconfigure(encoding='utf-8', errors='replace')


logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)

# 워커 스레드가 어느 기기를 처리 중인지 로그 분류용 태깅
_TLS = threading.local()


class DeviceLogHandler(logging.Handler):
    """기기별 최근 로그를 메모리에만 보관합니다(파일 기록 없음).

    진행률 화면이 콘솔을 차지하는 동안에도 루트 로거에 핸들러가 최소 1개
    남아 있게 하는 역할도 겸합니다 — 핸들러가 0개면 logging.lastResort가
    WARNING 이상을 stderr로 흘려 진행률 화면을 오염시킵니다.
    실행 종료 후 실패한 기기의 직전 로그만 tail()로 꺼내 보여줍니다.
    """

    def __init__(self, maxlen=40):
        super().__init__()
        self.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))
        self._buffers = collections.defaultdict(lambda: collections.deque(maxlen=maxlen))
        self._buffer_lock = threading.Lock()

    def emit(self, record):
        try:
            serial = getattr(_TLS, 'serial', None)
            if not serial:
                match = re.match(r'\[([A-Za-z0-9]+)\]', record.getMessage())
                serial = match.group(1) if match else '_global'
            with self._buffer_lock:
                self._buffers[serial].append(self.format(record))
        except Exception:
            pass  # 로그 수집 실패가 초기화를 방해하면 안 됨

    def tail(self, serial, count=15):
        with self._buffer_lock:
            return list(self._buffers.get(serial, []))[-count:]


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


def _console_size():
    """콘솔 '창'의 (가로, 세로)를 반환합니다.

    Windows에서 shutil.get_terminal_size()는 창이 아니라 화면 버퍼 크기를
    돌려줄 수 있습니다. 버퍼가 창보다 넓으면 실제로는 줄바꿈이 일어나는데도
    넉넉한 너비로 착각해 커서 계산이 어긋나므로, srWindow에서 직접 읽습니다.
    """
    if os.name == 'nt':
        try:
            import ctypes

            class _COORD(ctypes.Structure):
                _fields_ = [('X', ctypes.c_short), ('Y', ctypes.c_short)]

            class _SMALL_RECT(ctypes.Structure):
                _fields_ = [('Left', ctypes.c_short), ('Top', ctypes.c_short),
                            ('Right', ctypes.c_short), ('Bottom', ctypes.c_short)]

            class _SCREEN_BUFFER_INFO(ctypes.Structure):
                _fields_ = [('dwSize', _COORD), ('dwCursorPosition', _COORD),
                            ('wAttributes', ctypes.c_ushort), ('srWindow', _SMALL_RECT),
                            ('dwMaximumWindowSize', _COORD)]

            info = _SCREEN_BUFFER_INFO()
            kernel32 = ctypes.windll.kernel32
            if kernel32.GetConsoleScreenBufferInfo(kernel32.GetStdHandle(-11), ctypes.byref(info)):
                window = info.srWindow
                return (window.Right - window.Left + 1, window.Bottom - window.Top + 1)
        except Exception:
            pass
    try:
        size = shutil.get_terminal_size((80, 25))
        return (size.columns, size.lines)
    except Exception:
        return (80, 25)


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
                         'model': '', 'series': '', 'name': '', 'lang': '', 'note': '',
                         'started_at': None, 'finished_at': None}
                for serial in serials
            }
            self.started_at = time.time()
            self.active = True
        for handler in list(logging.getLogger().handlers):
            if isinstance(handler, logging.StreamHandler):
                self.console_handler = handler
                logging.getLogger().removeHandler(handler)
        # 전용 화면 버퍼로 전환 + 커서 숨김 + 자동 줄바꿈 해제.
        # 매 프레임 좌상단(\x1b[H)부터 다시 그리므로 '몇 줄 올릴지' 계산이 필요 없고,
        # 폭·높이 계산이 틀려도 출력이 아래로 누적될 수 없다.
        sys.stdout.write('\x1b[?1049h\x1b[?25l\x1b[?7l\x1b[2J')
        sys.stdout.flush()
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
        final_lines = self._compose()
        self.active = False
        # 전용 화면을 빠져나오면 진행 화면은 사라지므로, 원래 화면에 결과를 다시 남긴다
        sys.stdout.write('\x1b[?7h\x1b[?25h\x1b[?1049l')
        sys.stdout.write('\n'.join(final_lines) + '\n\n')
        sys.stdout.flush()
        if self.console_handler:
            logging.getLogger().addHandler(self.console_handler)
            self.console_handler = None

    # --- 워커 스레드가 호출하는 보고용 메서드 ---

    def set_info(self, serial, model, series, name='', lang=''):
        self._update(serial, model=model, series=series, name=name, lang=lang)

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
            try:
                self._draw()
            except Exception:
                # 한 프레임 실패(콘솔 리사이즈, 인코딩 등)가 UI 전체를 멈추면 안 됨
                pass
            self.stop_event.wait(0.15)

    def _draw(self):
        """좌상단부터 다시 그립니다. 이전 프레임 줄 수를 추적하지 않습니다."""
        columns, _ = _console_size()
        lines = [self._clip(line, max(8, columns - 1)) for line in self._compose()]
        buffer = ['\x1b[H']                       # 커서를 항상 좌상단으로
        buffer += ['\x1b[2K' + line + '\r\n' for line in lines]
        buffer.append('\x1b[J')                   # 이전 프레임이 더 길었을 경우 잔여물 제거
        sys.stdout.write(''.join(buffer))
        sys.stdout.flush()

    def _compose(self):
        """현재 상태를 화면에 그릴 줄 목록으로 만듭니다(색 코드 포함)."""
        columns, rows = _console_size()
        columns = max(24, columns)

        with self.lock:
            states = {serial: dict(self.states[serial]) for serial in self.order}
            order = list(self.order)
            elapsed = time.time() - self.started_at

        completed = sum(1 for s in states.values() if s['status'] == 'done')
        failed = sum(1 for s in states.values() if s['status'] == 'fail')
        all_settled = (completed + failed) == len(order) and order
        rule = '═' * max(8, min(68, columns - 2))
        # 기기가 많아 창 높이를 넘으면 아래쪽 기기가 안 보이므로 기기당 2줄로 접는다
        compact = (5 + len(order) * 3) > rows
        # 접었을 때는 막대 뒤에 상태 문구가 붙으므로 막대를 좁혀 자리를 남긴다
        bar_width = max(8, min(18, columns - 46)) if compact else max(8, min(34, columns - 26))
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

            # 기기 이름(자산 라벨)이 있으면 맨 앞에 — 20대 중 어느 폰인지 바로 찾는 용도
            name = (state['name'] + '  ') if state['name'] else ''
            model = (' ' + state['model']) if state['model'] else ''
            series = (' ' + state['series']) if state['series'] else ''
            lines.append(head_color + ' [%d] %s%s%s%s' % (position, name, serial, model, series) +
                         self.RESET +
                         (self.DIM + '  ' + state['lang'] + self.RESET if state['lang'] else ''))

            filled = int(round(fraction * bar_width))
            bar = (self.DIM + '[' + self.RESET + bar_color + self.fill_char * filled + self.RESET +
                   self.DIM + self.empty_char * (bar_width - filled) + ']' + self.RESET)
            percent_color = self.GREEN if status == 'done' else (self.RED if status == 'fail' else self.WHITE)
            lines.append('     ' + bar + '  ' + percent_color + '%3d%%' % round(fraction * 100) + self.RESET +
                         self.DIM + '  %2d/%d' % (index, self.total_steps) + self.RESET)

            if status == 'done':
                spent = (state['finished_at'] or 0) - (state['started_at'] or state['finished_at'] or 0)
                status_line = (self.GREEN + '√ 초기화 완료' + self.RESET +
                               self.DIM + ' (%s)' % self._mmss(spent) + self.RESET)
            elif status == 'fail':
                status_line = self.RED + '× ' + state['label'] + self.RESET
            elif status == 'wait':
                status_line = self.DIM + '· 대기 중' + self.RESET
            elif state['note']:
                status_line = self.YELLOW + '! ' + state['note'] + self.RESET
            else:
                spin = self.SPINNER[int(time.time() * 6) % len(self.SPINNER)]
                status_line = self.CYAN + '%s %s' % (spin, state['label']) + self.RESET

            if compact:
                lines[-1] += '  ' + status_line  # 막대 줄 뒤에 붙여 한 줄 절약
            else:
                lines.append('     ' + status_line)

        lines.append(self.DIM + rule + self.RESET)
        if all_settled and failed:
            lines.append(self.RED + ' 실패 %d대 — 다시 초기화하세요.' % failed + self.RESET)
        elif all_settled:
            lines.append(self.GREEN + ' 모든 기기 초기화 완료.' + self.RESET)
        else:
            lines.append(self.DIM + ' 완료된 기기부터 분리하세요.' + self.RESET)

        return lines

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


class RestorationError(RuntimeError):
    """임시 비활성화 앱 복구 실패: 이후 삭제 작업을 계속하면 안 됩니다."""


class DeviceLostError(RuntimeError):
    """adb가 기기를 더 이상 보지 못함 — 해당 기기의 파이프라인 즉시 중단용."""

    def __init__(self, serial, detail):
        self.serial = serial
        self.detail = detail
        super().__init__('기기 연결 끊김 (%s)' % detail)


# adb 클라이언트가 기기 이탈 시 stderr로 내는 메시지 패턴 (소문자 비교)
DEVICE_GONE_PATTERNS = (
    'not found', 'device offline', 'device unauthorized',
    'error: closed', 'connection reset', 'protocol fault',
)


def _exec(cmd, timeout):
    """subprocess 실행의 유일한 통로 — 시뮬레이션 테스트가 이 함수만 패치합니다."""
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          encoding='utf-8', errors='replace')


def _adb_serial(cmd):
    """['adb', '-s', serial, ...] 형태에서 시리얼을 추출합니다. 없으면 None."""
    if len(cmd) >= 3 and cmd[0] == 'adb' and cmd[1] == '-s':
        return cmd[2]
    return None


def device_alive(serial, timeout=5):
    """기기가 여전히 adb에 정상 연결되어 있는지 확인합니다(raise 없음)."""
    try:
        result = _exec(['adb', '-s', serial, 'get-state'], timeout)
        return result.returncode == 0 and result.stdout.strip() == 'device'
    except Exception:
        return False


def _stderr_says_device_gone(stderr):
    lowered = (stderr or '').lower()
    return any(pattern in lowered for pattern in DEVICE_GONE_PATTERNS)


def run_command(cmd, check=False, timeout=90, retries=1):
    """주어진 명령어를 실행하고 결과를 반환합니다. 실패 시 재시도합니다.

    best-effort 철학 유지: 일반 실패(패키지 없음 등)는 결과를 그대로 돌려주지만,
    stderr가 기기 이탈을 가리키고 get-state로도 이탈이 확인되면 DeviceLostError를
    던져 남은 단계가 조용히 no-op로 흘러가는 것을 막습니다.
    """
    serial = _adb_serial(cmd)
    for attempt in range(1 + retries):
        try:
            result = _exec(cmd, timeout)
            # 보안 폴더(user 150) 접근 에러는 무시 — shell 권한으로 접근 불가
            if result.stderr and 'SecurityException' in result.stderr and 'user 150' in result.stderr:
                logging.debug('[보안폴더] 무시: %s', result.stderr.strip().split('\n')[0])
                result = subprocess.CompletedProcess(cmd, returncode=0, stdout=result.stdout, stderr='')
            if result.returncode != 0 and serial and _stderr_says_device_gone(result.stderr):
                # 앱 출력의 우연한 'not found'와 구분하기 위해 실제 연결 상태로 확정
                if not device_alive(serial):
                    raise DeviceLostError(serial, (result.stderr or '').strip().split('\n')[0])
            # Preserve a real command failure after the final retry; only an actual
            # subprocess timeout should fall through to the synthetic TIMEOUT result.
            if result.returncode == 0 or not check or attempt == retries:
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
    # 최종 타임아웃 — 기기가 이탈해서 응답이 없는 것인지 확인
    if serial and not device_alive(serial):
        raise DeviceLostError(serial, '타임아웃 + 연결 없음')
    return subprocess.CompletedProcess(cmd, returncode=-1, stdout='', stderr='TIMEOUT')


def _reenable_packages(serial, packages):
    failures = []
    for pkg in packages:
        try:
            _pc_required(serial, ['shell', 'pm', 'enable', '--user', '0', pkg])
            active = _pc_required(serial, ['shell', 'pm', 'list', 'packages', '-e', '--user', '0', pkg])
            if 'package:' + pkg not in active.stdout.splitlines():
                failures.append(pkg)
        except DeviceLostError:
            raise
        except Exception:
            failures.append(pkg)
    if failures:
        raise RestorationError('앱 재활성화 복구 실패: ' + ', '.join(failures))


# 상태별 조치 안내 (scan_devices가 문제 기기 표에 사용)
DEVICE_STATE_HINTS = {
    'unauthorized': '기기 화면에서 USB 디버깅 허용(RSA)을 승인하세요',
    'offline': 'USB 케이블을 다시 꽂아보세요 (허브 전원 부족 가능)',
    'authorizing': '잠시 후 다시 스캔하세요 (인증 진행 중)',
    'recovery': '기기를 정상 부팅한 뒤 다시 연결하세요',
    'sideload': '기기를 정상 부팅한 뒤 다시 연결하세요',
    'no permissions': 'USB 연결을 다시 시도하세요 (권한 문제)',
}


def scan_devices():
    """연결된 모든 기기를 상태와 함께 스캔합니다.

    반환: (ready, problems)
      ready    — 초기화 가능한 시리얼 목록 (상태 'device')
      problems — [(serial, state), ...] 초기화 불가능하지만 물리적으로 연결된 기기
    이전 구현은 'device' 외 상태를 조용히 버려서, 허브에서 일시적으로
    offline/unauthorized인 기기가 티 나지 않게 누락되는 원인이었습니다.
    """
    result = run_command(['adb', 'devices'], timeout=15)
    ready, problems = [], []
    if not hasattr(result, 'stdout') or not result.stdout:
        return ready, problems
    for line in result.stdout.strip().splitlines():
        line = line.strip()
        # 헤더/데몬 배너는 인덱스가 아니라 내용으로 거른다
        if not line or line.startswith('List of devices') or line.startswith('*'):
            continue
        parts = line.split('\t')
        if len(parts) < 2:
            continue
        serial, state = parts[0].strip(), parts[1].strip()
        if state == 'device':
            ready.append(serial)
        else:
            problems.append((serial, state))
    return ready, problems


def get_connected_devices():
    """연결된 ADB 디바이스 목록을 가져옵니다(하위 호환용)."""
    ready, _ = scan_devices()
    return ready


MODEL_TO_SERIES = {
    'S91': 'S23', 'S92': 'S24', 'S93': 'S25', 'S94': 'S26',
}


def get_device_model(serial):
    """모델명(예: SM-S948N)을 반환합니다. 조회 실패 시 빈 문자열."""
    result = run_command(['adb', '-s', serial, 'shell', 'getprop', 'ro.product.model'])
    return result.stdout.strip() if hasattr(result, 'stdout') and result.stdout else ''


def get_device_name(serial):
    """기기에 설정된 이름(예: S24F57 — 자산 라벨)을 반환합니다. 없으면 빈 문자열."""
    result = run_command(['adb', '-s', serial, 'shell', 'settings', 'get', 'global', 'device_name'])
    name = result.stdout.strip() if hasattr(result, 'stdout') and result.stdout else ''
    return '' if name == 'null' else name


def series_from_model(model, serial=''):
    """모델명 문자열에서 시리즈를 판정합니다(추가 adb 호출 없음)."""
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


def detect_series(serial):
    """모델명(getprop)으로 디바이스 시리즈를 자동 감지합니다."""
    return series_from_model(get_device_model(serial), serial)


def clear_app_data(serial, package, desc):
    """특정 앱의 데이터를 초기화합니다."""
    cleared = getattr(_TLS, 'cleared', None)
    if cleared is not None and package in cleared:
        return
    logging.info('[%s] %s 데이터 초기화 중...', serial, desc)
    listed = _pc_required(serial, ['shell','pm','list','packages','--user','0',package])
    if 'package:' + package not in listed.stdout.splitlines():
        return
    result = _pc_required(serial, ['shell','pm','clear','--user','0',package], timeout=120)
    if result.stdout.strip() != 'Success':
        raise RuntimeError(desc + ' 데이터 초기화 실패')
    if cleared is not None:
        cleared.add(package)


def clear_app_group(serial, apps):
    failures = []
    for package, name in apps:
        try:
            clear_app_data(serial, package, name)
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))
    if failures:
        raise RuntimeError('; '.join(failures))


# ============================================================
# 1. 언어 설정
# ============================================================

# name: 선택 메뉴용(원어 병기), ko_name: 배정 표 등 한글 표시용
LANGUAGE_OPTIONS = {
    '1': {'locale': 'ja-JP', 'name': '日本語 (일본어)', 'ko_name': '일본어'},
    '2': {'locale': 'en-US', 'name': 'English (영어)', 'ko_name': '영어'},
    '3': {'locale': 'ko-KR', 'name': '한국어', 'ko_name': '한국어'},
    '4': {'locale': 'zh-CN', 'name': '中文简体 (중국어 간체)', 'ko_name': '중국어 간체'},
    '5': {'locale': 'zh-TW', 'name': '中文繁體 (중국어 번체)', 'ko_name': '중국어 번체'},
}


# 대화형 화면(메뉴·표)의 색상 사용 여부 — main()에서 콘솔 확인 후 켠다
ANSI_OK = False

_C_DIM, _C_WHITE, _C_CYAN, _C_GREEN, _C_YELLOW, _C_RED = '90', '97', '96', '92', '93', '91'


def _c(text, code):
    """ANSI 색 입히기 (콘솔이 지원할 때만)."""
    return '\x1b[%sm%s\x1b[0m' % (code, text) if ANSI_OK else text


def _menu_cell(key, name, pad_to=0):
    """'1. 日本語 (일본어)' 형태의 셀. 전각 폭 기준으로 패딩해 열을 맞춘다."""
    plain = '%s. %s' % (key, name)
    cell = _c(key + '.', _C_CYAN) + ' ' + name
    if pad_to:
        cell += ' ' * max(0, pad_to - _display_width(plain))
    return cell


def select_language():
    """사용자에게 언어 선택 메뉴를 표시합니다."""
    items = [(key, option['name']) for key, option in LANGUAGE_OPTIONS.items()]
    items.append(('0', '언어 변경 안함 (건너뛰기)'))
    rule = '─' * 56
    print()
    print(_c(rule, _C_DIM))
    print('  ' + _c('언어 설정을 선택해주세요', _C_WHITE))
    print(_c(rule, _C_DIM))
    for row_start in range(0, len(items), 2):
        pair = items[row_start:row_start + 2]
        line = '  ' + _menu_cell(*pair[0], pad_to=28)
        if len(pair) == 2:
            line += _menu_cell(*pair[1])
        print(line)
    print(_c(rule, _C_DIM))

    while True:
        choice = input('번호 입력: ').strip()
        if choice == '0':
            return None
        if choice in LANGUAGE_OPTIONS:
            selected = LANGUAGE_OPTIONS[choice]
            print('  ' + _c('→ %s 선택됨' % selected['name'], _C_GREEN) + '\n')
            return selected['locale']
        print('  ' + _c('잘못된 입력입니다. 다시 선택해주세요.', _C_YELLOW))


def _locale_display_name(locale):
    """배정 표에 쓰는 한글 언어 이름."""
    for option in LANGUAGE_OPTIONS.values():
        if option['locale'] == locale:
            return option['ko_name']
    return '언어 변경 안함'


def assign_languages(devices):
    """기본 언어를 고른 뒤, 일부 기기만 다른 언어로 바꿀 수 있게 합니다.

    반환: {serial: locale} — 혼합 배치(예: 일본어 반납 15대 + 중국어 반납 5대)를
    한 번의 실행으로 처리하기 위한 기기별 배정표.
    """
    default = select_language()

    infos = []
    for serial in devices:
        try:
            name = get_device_name(serial)
            model = get_device_model(serial)
        except DeviceLostError:
            # 스캔과 배정 사이에 뽑힌 기기 — 표에는 남기고 실행 단계에서 실패 처리
            name, model = '', ''
        infos.append((serial, name, model))

    locales = {serial: default for serial in devices}
    while True:
        print('\n' + _c('기기별 언어 배정:', _C_WHITE))
        for index, (serial, name, model) in enumerate(infos, start=1):
            print(_c(' %2d.' % index, _C_CYAN) +
                  ' %-10s %-16s %-10s ' % (name or '-', serial, model or '-') +
                  _c('→ ', _C_DIM) + _c(_locale_display_name(locales[serial]), _C_GREEN))
        if len(devices) == 1:
            # 1대는 방금 고른 언어 외 선택지가 없으므로 표만 확인시키고 진행
            return locales
        entry = input('다른 언어로 바꿀 기기 번호 (쉼표, 예: 3,5) — 없으면 Enter: ').strip()
        if not entry:
            return locales
        try:
            numbers = [int(token) for token in re.split(r'[,\s]+', entry) if token]
        except ValueError:
            print('  잘못된 입력입니다. 번호만 입력해주세요.')
            continue
        targets = [infos[n - 1][0] for n in numbers if 1 <= n <= len(infos)]
        if not targets:
            print('  잘못된 입력입니다. 표의 번호 범위에서 입력해주세요.')
            continue
        chosen = select_language()
        for serial in targets:
            locales[serial] = chosen


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
        # 재시도 결과를 실제로 검증해 반환 — 무조건 True를 돌려주면
        # 호출부의 폴백 경로(pm clear 등)가 필요할 때 타지 않는다
        check = run_command(['adb', '-s', serial, 'shell', 'ls', remote_path])
        if hasattr(check, 'returncode') and check.returncode != 0:
            logging.warning('[%s] DEX push 최종 실패: %s', serial, dex_name)
            return False
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
    """검증된 사용자 0 계정 목록. 조회 실패를 빈 목록으로 간주하지 않습니다."""
    return [{'type': kind} for kind in naver_account_cleanup.accounts(serial, run_command)]


def is_samsung_account(account_type):
    """삼성 계정인지 확인합니다."""
    for samsung_type in SAMSUNG_ACCOUNT_TYPES:
        if account_type.startswith(samsung_type):
            return True
    return False


def verify_non_samsung_accounts_removed(serial):
    remaining = get_device_accounts(serial)
    non_samsung = [a for a in remaining if not is_samsung_account(a['type'])]
    if non_samsung:
        raise RuntimeError('비삼성 계정 %d개가 기기에 남아 있습니다 · 계정 삭제 실패' % len(non_samsung))


def clear_account_sessions(serial):
    failures = []
    for pkg, name in [(NMAP_PACKAGE, '네이버 지도'), (naver_account_cleanup.MEET, 'Google Meet')]:
        try:
            clear_app_data(serial, pkg, name)
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))
    if failures:
        raise RuntimeError('; '.join(failures))


def remove_non_samsung_accounts(serial):
    """백그라운드 삭제 + NAVER 설정 UI 폴백 후 실제 잔존 계정을 검증합니다."""
    logging.info('[%s] 삼성 계정 제외 전체 계정 삭제 시작...', serial)
    if naver_account_cleanup.checked(serial, run_command,
            ['shell', 'am', 'get-current-user']).strip() != '0':
        raise RuntimeError('소유자(사용자 0) 화면에서만 계정을 삭제합니다.')
    accounts = get_device_accounts(serial)
    protected = sum(is_samsung_account(a['type']) for a in accounts)
    logging.info('[%s] 현재 계정 %d개 · 삼성 계정 보존 %d개', serial, len(accounts), protected)
    if len(accounts) == protected:
        logging.info('[%s] 비삼성 계정 없음 · 계정 제거 생략', serial)
        clear_account_sessions(serial)
        return
    try:
        if push_dex_if_needed(serial, 'account_preparer.dex'):
            prepared = run_command(['adb', '-s', serial, 'shell', 'CLASSPATH=/data/local/tmp/account_preparer.dex',
                                    'app_process', '/system/bin', 'AccountPreparer', '--prepare'], timeout=120, retries=0)
            if prepared.returncode == 0 and 'SUCCESS:AUTHENTICATORS_READY' in (prepared.stdout or ''):
                time.sleep(1)
            else:
                logging.warning('[%s] 일부 계정 인증 서비스 준비 실패 · 계정 제거와 최종 확인 계속', serial)
    except DeviceLostError:
        raise
    except Exception:
        logging.warning('[%s] 계정 인증 서비스 준비 실패 · 계정 제거와 최종 확인 계속', serial)
    if not push_dex_if_needed(serial, 'account_remover.dex'):
        raise RuntimeError('account_remover.dex 없음 · 계정 삭제를 완료하지 못했습니다.')
    # DEX output may contain customer identifiers. Verify inventory instead of logging it.
    run_command(['adb', '-s', serial, 'shell',
                 'CLASSPATH=/data/local/tmp/account_remover.dex',
                 'app_process', '/system/bin', 'AccountRemover'], timeout=120, retries=0)
    remaining = get_device_accounts(serial)
    if any(a['type'] == naver_account_cleanup.NAVER for a in remaining):
        logging.info('[%s] 네이버 계정 잔존 · 설정에서 기기 계정/인증 정보 제거 중...', serial)
        naver_account_cleanup.remove(serial, run_command)
    clear_account_sessions(serial)
    verify_non_samsung_accounts_removed(serial)
    logging.info('[%s] 비삼성 계정 0개 확인 · 삼성 계정 보존 · 계정 삭제 완료', serial)


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
    failures = []
    for pkg in clear_packages:
        try:
            clear_app_data(serial, pkg, pkg)
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))

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

    if failures:
        raise RuntimeError('; '.join(failures))
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
        'com.forholiday.galaxyreset',
        'moe.shizuku.privileged.api',
        'com.sec.android.app.sbrowser',
        'com.sec.knox.kccagent',  # Knox Configure 관리 앱·등록 보존
    ]
    result = run_command([
        'adb', '-s', serial, 'shell',
        'pm', 'list', 'packages', '--user', '0', '-3'
    ], timeout=120)
    if result.returncode != 0:
        raise RuntimeError('사용자 앱 목록 조회 실패 · 앱 삭제 완료로 처리하지 않습니다.')
    rows = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if any(not re.fullmatch(r'package:[A-Za-z0-9_.]+', row) for row in rows):
        raise RuntimeError('사용자 앱 목록 형식 확인 실패')
    installed_apps = [row[8:] for row in rows]

    failures = []
    for app in installed_apps:
        if app not in exclude_apps:
            logging.info('[%s] 앱 삭제: %s', serial, app)
            removed = run_command(['adb', '-s', serial, 'shell', 'pm', 'uninstall', '--user', '0', app])
            if removed.returncode != 0 or (removed.stdout or '').strip() != 'Success':
                failures.append(app)
        elif app == 'com.sec.knox.kccagent':
            logging.info('[%s] Knox Configure 관리 앱 보존 · 관리자 등록 유지', serial)
        else:
            logging.info('[%s] 앱 보존: %s', serial, app)
    if failures:
        raise RuntimeError('앱 삭제 실패: ' + ', '.join(failures))


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
        'com.nhn.android.nmap': '네이버 지도',
        'com.alphainventor.filemanager': '파일 관리자',
        'com.sec.android.app.popupcalculator': '계산기',
        'com.google.android.apps.tachyon': 'Google Meet',
    }
    logging.info('[%s] Google 앱 사용 기록 삭제 시작...', serial)
    failures = []
    for package, name in google_apps.items():
        try:
            clear_app_data(serial, package, name)
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))
    if failures:
        raise RuntimeError('; '.join(failures))
    logging.info('[%s] Google 앱 사용 기록 삭제 완료.', serial)


def reset_camera_settings(serial):
    """카메라 앱 데이터를 초기화하여 설정(화면비, 워터마크, 격자 등)을 기본값으로 되돌립니다."""
    camera_apps = {
        'com.sec.android.app.camera': '카메라',
        'com.samsung.android.app.cameraassistant': '카메라 어시스턴트',
    }
    logging.info('[%s] 카메라 설정 초기화 시작...', serial)
    clear_app_group(serial, camera_apps.items())
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
    failures = []
    def safe_clear(package, name):
        try:
            clear_app_data(serial, package, name)
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))

    if not push_dex_if_needed(serial, 'content_cleaner.dex'):
        logging.warning('[%s] content_cleaner.dex 없음 — 폴백: pm clear 사용', serial)
        safe_clear( 'com.android.providers.contacts', '주소록/통화기록')
        safe_clear( 'com.android.providers.telephony', 'SMS/MMS')
        if failures:
            raise RuntimeError('; '.join(failures))
        return

    result = run_command([
        'adb', '-s', serial, 'shell',
        'CLASSPATH=/data/local/tmp/content_cleaner.dex',
        'app_process', '/system/bin', 'ContentCleaner'
    ])

    stdout = result.stdout if hasattr(result, 'stdout') else ''
    needs_fallback = {'CALL_LOG': result.returncode != 0, 'SMS': result.returncode != 0, 'CONTACTS': result.returncode != 0}

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
                if parts[1] in needs_fallback:
                    needs_fallback[parts[1]] = True
                else:
                    failures.append('통화/문자/주소록 삭제 결과 확인 필요')
                logging.warning('[%s] %s 삭제 실패: %s', serial, parts[1],
                              parts[2] if len(parts) > 2 else '')

    # 실패한 항목에 대해 pm clear 폴백
    if needs_fallback.get('CALL_LOG') or needs_fallback.get('CONTACTS'):
        safe_clear( 'com.android.providers.contacts', '주소록/통화기록 (폴백)')
    if needs_fallback.get('SMS'):
        safe_clear( 'com.android.providers.telephony', 'SMS/MMS (폴백)')

    # 삼성 메시지 앱 데이터 초기화 (임시저장 문자 등 앱 자체 DB 삭제)
    safe_clear( 'com.samsung.android.messaging', '삼성 메시지 (임시저장 포함)')
    safe_clear( 'com.samsung.android.providers.contacts', '삼성 연락처 프로바이더')
    safe_clear( 'com.samsung.android.app.contacts', '삼성 연락처 앱')
    safe_clear( 'com.samsung.android.dsms', '삼성 DSMS')
    safe_clear( 'com.android.mms.service', 'MMS 서비스')

    if failures:
        raise RuntimeError('; '.join(failures))
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
        # 진행률 화면(전용 버퍼) 위에 print()가 겹쳐 쓰이면 깨지고 곧 지워져
        # 보이지 않는다 — 진행률 행의 노란 표시 + 종료 후 공지로 전달한다.
        if PROGRESS:
            PROGRESS.note(serial, 'e-SIM 수동 삭제 필요 — 기기에서 SIM 관리자 확인')
        add_post_run_notice('[%s] e-SIM 수동 삭제 필요 — SIM 관리자 > eSIM 선택 > 삭제 '
                            '(기기에 화면을 열어두었습니다)' % serial)
        logging.warning('[%s] ⚠ e-SIM 수동 삭제 필요 — SIM 관리자 화면을 열었습니다.', serial)

    logging.info('[%s] e-SIM 프로필 처리 완료', serial)


def clear_logs_and_cache(serial):
    failures = []
    try:
        clear_call_sms_contacts(serial)
    except DeviceLostError:
        raise
    except Exception as e:
        failures.append(str(e))
    try:
        clear_app_group(serial, [
            ('com.sec.android.gallery3d', '갤러리'), ('com.sec.android.themestore', '테마'),
            ('com.sec.android.app.vepreload', '삼성 스튜디오'),
            ('com.samsung.android.app.clipboardedge', '클립보드 엣지'),
            ('com.samsung.android.honeyboard', '삼성 키보드·클립보드')])
    except DeviceLostError:
        raise
    except Exception as e:
        failures.append(str(e))
    if failures:
        raise RuntimeError('; '.join(failures))


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
    # 수십 GB 삭제일 수 있어 넉넉한 타임아웃. '.*'는 '..'까지 잡아 비정상 종료를
    # 유발하므로 dotglob 안전 패턴('.[!.]*', '..?*')으로 숨김 파일만 지운다.
    for attempt in range(2):
        for paths in (['/storage/emulated/0/*'],
                      ['/storage/emulated/0/.[!.]*', '/storage/emulated/0/..?*']):
            deletion = run_command(['adb', '-s', serial, 'shell', 'rm', '-rf', *paths], timeout=300)
            if deletion.returncode in (-1, 124) or (deletion.stderr or '').strip() == 'TIMEOUT':
                raise RuntimeError('내부 저장소 삭제 시간 초과 · 재확인 필요')
        listing = _pc_required(serial, ['shell', 'find', '/storage/emulated/0', '-type', 'f'], timeout=120).stdout
        remaining = [path for path in listing.splitlines() if path.strip() and not path.endswith('/.nomedia')
                     and not ('/.thumbnails/' in path and path.endswith('/.database_uuid'))]
        if not remaining:
            logging.info('[%s] 내장 메모리 전체 삭제 완료 · 잔여 파일 0개 확인%s', serial,
                         ' (자동 재시도 후)' if attempt else '')
            return
        if attempt == 0:
            logging.warning('[%s] 내부 저장소 잔여 파일 %d개 · 해당 단계 자동 재시도', serial, len(remaining))
            time.sleep(.5)
    raise RuntimeError('내부 저장소 잔여 파일 %d개 · 자동 재시도 후에도 남음 · 삭제 완료로 처리하지 않습니다.' % len(remaining))


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
    if result.returncode != 0 or not (hasattr(result, 'stdout') and result.stdout):
        # 타임아웃/오류로 빈 결과가 오면 '위젯 없음'으로 오판하지 않는다
        logging.warning('[%s] 위젯 탐지 실패(dumpsys 오류) — 위젯이 남아있을 수 있음', serial)
        return
    dump = result.stdout

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
                if match.group(1) not in KEEP_APPS:
                    providers.add(match.group(1))

    if not providers:
        logging.info('[%s] 바인딩된 위젯 없음 — 스킵', serial)
        return

    logging.info('[%s] 탐지된 위젯 provider (%d개): %s',
                 serial, len(providers), ', '.join(sorted(providers)))

    for pkg in providers:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'disable-user', '--user', '0', pkg])
    try:
        # launcher clear 없이 재시작 → db row 검증 + 빈 슬롯 저장
        run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.app.launcher'])
        time.sleep(1)
        run_command(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                     'com.sec.android.app.launcher/.activities.LauncherActivity'])
        time.sleep(4)
    finally:
        # 중간에 무엇이 실패하든 provider가 비활성 상태로 남지 않게 한다
        _reenable_packages(serial, providers)

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
    store_pkgs = ['com.sec.android.app.samsungapps', 'com.samsung.android.scloud']
    for pkg in store_pkgs:
        run_command(['adb', '-s', serial, 'shell', 'pm', 'disable-user', '--user', '0', pkg])
    try:
        # launcher 재시작 - disabled 상태에서 db row 검증 trigger
        run_command(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'com.sec.android.app.launcher'])
        time.sleep(1)
        run_command(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                     'com.sec.android.app.launcher/.activities.LauncherActivity'])
        time.sleep(5)
    finally:
        # Galaxy Store/Cloud가 비활성 상태로 출고되는 일이 없도록 무조건 재활성화
        _reenable_packages(serial, store_pkgs)
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
    if result.returncode != 0 or not (hasattr(result, 'stdout') and result.stdout):
        # 타임아웃/오류로 빈 결과가 오면 '위젯 없음'으로 오판하지 않는다
        logging.warning('[%s] 위젯 탐지 실패(dumpsys 오류) — 위젯이 남아있을 수 있음', serial)
        return
    dump = result.stdout

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
                if match.group(1) not in KEEP_APPS:
                    providers.add(match.group(1))

    if not providers:
        logging.info('[%s] 바인딩된 위젯 없음 — 2차 clear 생략', serial)
        return

    logging.info('[%s] 탐지된 위젯 provider (%d개): %s',
                 serial, len(providers), ', '.join(sorted(providers)))

    # Journal the intended restore set before the first mutation.
    try:
        for pkg in providers:
            _pc_required(serial, ['shell', 'pm', 'disable-user', '--user', '0', pkg])
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
    finally:
        # Provider 재활성화 (앱 기능 복원) — 중간 실패 시에도 반드시 실행
        _reenable_packages(serial, providers)

    logging.info('[%s] 런처 초기화 완료 — 위젯 제거됨, provider 재활성화됨', serial)


PHONE_APP_PACKAGE = 'com.forholiday.galaxyreset'
SHIZUKU_PACKAGE = 'moe.shizuku.privileged.api'
PHONE_APK_FILES = {PHONE_APP_PACKAGE: 'galaxy-reset.apk', SHIZUKU_PACKAGE: 'shizuku.apk'}
NMAP_PACKAGE = 'com.nhn.android.nmap'
FILEMANAGER_PACKAGE = 'com.alphainventor.filemanager'
KEEP_APPS = frozenset({PHONE_APP_PACKAGE, SHIZUKU_PACKAGE, NMAP_PACKAGE, FILEMANAGER_PACKAGE,
    'com.sec.android.app.popupcalculator', 'com.sec.android.app.sbrowser', 'com.sec.knox.kccagent'})


def load_nmap_bundle(verify_hash=False):
    try:
        with open(resource_path('nmap-bundle.json'), encoding='utf-8') as source:
            bundle = json.load(source)
        if bundle['package'] != NMAP_PACKAGE or not isinstance(bundle['versionCode'], int):
            raise ValueError('네이버 지도 메타데이터 오류')
        names = [item['file'] for item in bundle['apks']]
        if not names or names[0] != 'nmap.apk' or len(names) != len(set(names)):
            raise ValueError('네이버 지도 기본 APK/분할 목록 오류')
        paths = []
        for item in bundle['apks']:
            if not re.fullmatch(r'nmap(?:-[A-Za-z0-9_.]+)?\.apk', item['file']):
                raise ValueError('잘못된 네이버 지도 APK 이름')
            path = resource_path(item['file'])
            with zipfile.ZipFile(path) as archive:
                if 'AndroidManifest.xml' not in archive.namelist():
                    raise ValueError('유효하지 않은 네이버 지도 APK')
            if verify_hash:
                digest = hashlib.sha256()
                with open(path, 'rb') as binary:
                    for block in iter(lambda: binary.read(1024 * 1024), b''):
                        digest.update(block)
                if digest.hexdigest() != item['sha256']:
                    raise ValueError('네이버 지도 APK 해시 불일치: ' + item['file'])
            paths.append(path)
        return dict(bundle, apk_path=paths[0], apk_paths=paths)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        raise RuntimeError('네이버 지도 동봉 파일 검증 실패: %s' % error) from error


def load_filemanager_bundle(verify_hash=False):
    with open(resource_path('filemanager-bundle.json'), encoding='utf-8') as source:
        app = json.load(source)
    if app['package'] != FILEMANAGER_PACKAGE or app['file'] != 'filemanager.apk' or not isinstance(app['versionCode'], int):
        raise RuntimeError('파일 관리자 APK 메타데이터 오류')
    path = resource_path(app['file'])
    if verify_hash:
        with open(path, 'rb') as source:
            if hashlib.sha256(source.read()).hexdigest() != app['sha256']:
                raise RuntimeError('파일 관리자 APK 해시 불일치')
    return dict(app, apk_path=path)


def load_phone_bundle(verify_hash=False):
    """EXE와 함께 동봉한 서명 APK를 검증합니다. 설치 때 다른 키로 재빌드하지 않습니다."""
    manifest_path = resource_path('phone-app/bundle.json')
    try:
        with open(manifest_path, encoding='utf-8') as source:
            manifest = json.load(source)
        apps = {app['package']: dict(app) for app in manifest['apps']}
        if set(apps) != set(PHONE_APK_FILES):
            raise ValueError('필수 APK 목록 불일치')
        for package, filename in PHONE_APK_FILES.items():
            app = apps[package]
            if app['file'] != filename or not isinstance(app['versionCode'], int):
                raise ValueError('APK 메타데이터 오류')
            apk = resource_path('phone-app/' + filename)
            with zipfile.ZipFile(apk) as archive:
                if not {'AndroidManifest.xml', 'classes.dex'} <= set(archive.namelist()):
                    raise ValueError('유효하지 않은 APK: ' + filename)
            if verify_hash:
                digest = hashlib.sha256()
                with open(apk, 'rb') as binary:
                    for block in iter(lambda: binary.read(1024 * 1024), b''):
                        digest.update(block)
                if digest.hexdigest() != app['sha256']:
                    raise ValueError('APK 해시 불일치: ' + filename)
            app['apk_path'] = apk
        return apps
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        raise RuntimeError('폰 앱 동봉 파일 검증 실패: %s — tools/prepare_phone_bundle.py를 실행하고 EXE를 다시 빌드하세요.' % error) from error


def _pc_required(serial, arguments, timeout=90):
    result = run_command(['adb', '-s', serial] + arguments, check=True, timeout=timeout)
    output = (result.stdout or '') + '\n' + (result.stderr or '')
    if result.returncode != 0 or any(token in output for token in ('SecurityException', 'Error:', 'Failure [')):
        # 설치/설정 실패를 '초기화 완료'로 넘기지 않는다. 연결 이탈은 run_command가 구분한다.
        raise RuntimeError('폰 앱 준비 실패 (%s): %s' % (' '.join(arguments[:3]), output.strip()[:180]))
    return result


def _package_version(serial, package):
    result = _pc_required(serial, ['shell', 'dumpsys', 'package', package])
    match = re.search(r'\bversionCode=(\d+)\b', result.stdout or '')
    if not match:
        raise RuntimeError('설치 버전 조회 실패: ' + package)
    return int(match.group(1))


def start_shizuku_from_pc(serial):
    """13.6+ 공식 starter 실행. 무선 페어링 키를 복제하거나 승인 DB를 수정하지 않습니다."""
    running = run_command(['adb', '-s', serial, 'shell', 'pidof', 'shizuku_server'])
    if running.returncode == 0 and re.fullmatch(r'[0-9\s]+', (running.stdout or '').strip()):
        logging.info('[%s] Shizuku 이미 실행 중', serial)
        return
    package_dump = _pc_required(serial, ['shell', 'dumpsys', 'package', SHIZUKU_PACKAGE]).stdout or ''
    path_match = re.search(r'^\s*codePath=(/data/app/[A-Za-z0-9_./+=~-]+)\s*$', package_dump, re.MULTILINE)
    abi_match = re.search(r'^\s*primaryCpuAbi=(\S+)\s*$', package_dump, re.MULTILINE)
    arch = {'arm64-v8a': 'arm64', 'armeabi-v7a': 'arm', 'x86_64': 'x86_64', 'x86': 'x86'}
    if not path_match or not abi_match or abi_match.group(1) not in arch:
        raise RuntimeError('Shizuku 실행 파일 경로/아키텍처 조회 실패')
    directory = path_match.group(1)
    starter = directory + '/lib/' + arch[abi_match.group(1)] + '/libshizuku.so'
    _pc_required(serial, ['shell', 'test', '-x', starter])
    command = shlex.quote(starter) + ' --apk=' + shlex.quote(directory + '/base.apk')
    _pc_required(serial, ['shell', command], timeout=45)
    for _ in range(10):
        running = run_command(['adb', '-s', serial, 'shell', 'pidof', 'shizuku_server'], timeout=10)
        if running.returncode == 0 and re.fullmatch(r'[0-9\s]+', (running.stdout or '').strip()):
            logging.info('[%s] Shizuku USB 기동 확인', serial)
            return
        time.sleep(1)
    raise RuntimeError('Shizuku 서버 시작 확인 실패')


def prepare_wireless_reset(serial):
    """첫 USB 작업에서 설정 가능한 범위까지 준비하고 최초 승인 절차는 직원에게 안내합니다."""
    sdk_text = _pc_required(serial, ['shell', 'getprop', 'ro.build.version.sdk']).stdout.strip()
    if not sdk_text.isdigit() or int(sdk_text) < 30:
        raise RuntimeError('폰 단독 무선 초기화는 Android 11 이상이 필요합니다.')
    user = _pc_required(serial, ['shell', 'am', 'get-current-user']).stdout.strip()
    if user != '0':
        raise RuntimeError('폰 앱 준비는 소유자(사용자 0) 화면에서 진행하세요.')
    sdk = int(sdk_text)
    for package in (PHONE_APP_PACKAGE, SHIZUKU_PACKAGE):
        _ensure_package_enabled(serial, package)
        _pc_required(serial, ['shell', 'pm', 'grant', '--user', '0', package,
                              'android.permission.WRITE_SECURE_SETTINGS'])
        if sdk >= 33:
            _pc_required(serial, ['shell', 'pm', 'grant', '--user', '0', package,
                                  'android.permission.POST_NOTIFICATIONS'])
        battery = run_command(['adb', '-s', serial, 'shell', 'cmd', 'deviceidle', 'whitelist', '+' + package])
        if battery.returncode != 0:
            add_post_run_notice('[%s] %s 배터리 제한 제외 실패 — 기기 설정에서 확인하세요.' % (serial, package))
    for name in ('development_settings_enabled', 'adb_enabled', 'adb_wifi_enabled'):
        _pc_required(serial, ['shell', 'settings', 'put', 'global', name, '1'])
    _pc_required(serial, ['shell', 'settings', 'put', 'global', 'adb_allowed_connection_time', '0'])
    auth_timeout = _pc_required(serial, ['shell', 'settings', 'get', 'global',
                                       'adb_allowed_connection_time']).stdout.strip()
    if auth_timeout != '0':
        raise RuntimeError('ADB 인증 자동 만료 해제 확인 실패 — 개발자 옵션의 ADB 인증 시간 제한 설정을 확인하세요.')
    start_shizuku_from_pc(serial)
    phone_autostart.prepare(serial, _exec)
    wireless = _pc_required(serial, ['shell', 'settings', 'get', 'global', 'adb_wifi_enabled']).stdout.strip()
    if wireless != '1':
        raise RuntimeError('무선 디버깅 활성화 확인 실패 — Wi-Fi 연결과 기기의 허용 팝업을 확인하세요.')
    _pc_required(serial, ['shell', 'am', 'start', '--user', '0', '-n', PHONE_APP_PACKAGE + '/.MainActivity'])
    note = ('[%s] 폰 앱·Shizuku 설치 및 앱 전용 자동 시작 인증 등록 완료. ADB 인증 자동 만료 해제 확인 완료. '
            '초기화 앱의 Shizuku 권한 연결도 확인했습니다. 이후 Wi-Fi 연결 상태에서 초기화 앱을 열면 자동 시작을 시도합니다. '
            '기존 Shizuku 페어링·권한은 보존하며, 앱 전용 인증을 별도로 등록했습니다.' % serial)
    add_post_run_notice(note)
    if PROGRESS:
        PROGRESS.note(serial, '자동 시작 인증 등록·Shizuku 앱 권한 연결 확인 완료')
    logging.info('[%s] 무선 초기화 준비 설정 완료', serial)


def ensure_essential_apps_installed(serial):
    """기존 필수 앱 + 폰 초기화 앱 + Shizuku를 설치하고 다음 폰 단독 실행을 준비합니다."""
    bundled = load_phone_bundle()
    apps = [
        dict(load_nmap_bundle(), name='네이버 지도'),
        dict(load_filemanager_bundle(), name='File Manager'),
        dict(bundled[PHONE_APP_PACKAGE], name='갤럭시 초기화'),
        dict(bundled[SHIZUKU_PACKAGE], name='Shizuku'),
    ]
    listed = _pc_required(serial, ['shell', 'pm', 'list', 'packages', '--user', '0'], timeout=120)
    installed = {line[8:].strip() for line in listed.stdout.splitlines() if line.startswith('package:')}
    failures = []
    for app in apps:
        try:
            package = app['package']
            if package in installed:
                if 'versionCode' not in app or _package_version(serial, package) >= app['versionCode']:
                    logging.info('[%s] %s 설치됨 (같거나 최신 버전 유지)', serial, app['name'])
                    continue
            apk = app['apk_path']
            if not os.path.isfile(apk):
                raise RuntimeError('필수 APK 없음: ' + apk)
            logging.info('[%s] %s 설치/업데이트 중...', serial, app['name'])
            paths = app.get('apk_paths', [apk])
            command = 'install-multiple' if len(paths) > 1 else 'install'
            result = run_command(['adb', '-s', serial, command, '-r', '--user', '0'] + paths, check=True, timeout=600)
            downgrade = 'INSTALL_FAILED_VERSION_DOWNGRADE' in ((result.stdout or '') + (result.stderr or ''))
            if not downgrade and (result.returncode != 0 or 'Success' not in (result.stdout or '')):
                raise RuntimeError('필수 앱 설치 확인 실패: ' + app['name'] + ' · ' + ((result.stdout or '') + (result.stderr or '')).strip()[:180])
            verify = _pc_required(serial, ['shell', 'pm', 'path', '--user', '0', package])
            if not (verify.stdout or '').strip().startswith('package:'):
                raise RuntimeError('필수 앱 설치 후 조회 실패: ' + app['name'])
            if 'versionCode' in app and _package_version(serial, package) < app['versionCode']:
                raise RuntimeError('필수 앱 버전 확인 실패: ' + app['name'])
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))
    for app in apps:
        if app['package'] == FILEMANAGER_PACKAGE:
            continue
        try:
            _ensure_package_enabled(serial, app['package'])
        except DeviceLostError:
            raise
        except Exception as e:
            failures.append(str(e))
    # Installation success is independent of Samsung deep-sleep activation.
    try:
        _ensure_package_enabled(serial, FILEMANAGER_PACKAGE)
    except DeviceLostError:
        raise
    except Exception:
        add_post_run_notice('[%s] 파일 관리자 설치 유지 · 딥 슬립/활성 상태 확인 필요 (초기화 계속)' % serial)
    try:
        verify_nmap_ready(serial)
    except DeviceLostError:
        raise
    except Exception as e:
        failures.append(str(e))
    if failures:
        raise RuntimeError('; '.join(failures))


def _ensure_package_enabled(serial, package):
    # Avoid a package state change (and process restart) for already active apps.
    query = ['shell', 'pm', 'list', 'packages', '-e', '--user', '0', package]
    if 'package:' + package in _pc_required(serial, query).stdout.splitlines():
        return
    _pc_required(serial, ['shell', 'pm', 'enable', '--user', '0', package])
    if 'package:' + package not in _pc_required(serial, query).stdout.splitlines():
        raise RuntimeError('필수 앱 활성 상태 확인 실패: ' + package)


def verify_nmap_ready(serial):
    """네이버 지도를 실행하지 않고 설치·최소 버전·실제 활성 상태를 확인합니다."""
    path = _pc_required(serial, ['shell', 'pm', 'path', '--user', '0', NMAP_PACKAGE])
    if not path.stdout.strip().startswith('package:'):
        raise RuntimeError('네이버 지도 설치 확인 실패')
    if _package_version(serial, NMAP_PACKAGE) < load_nmap_bundle()['versionCode']:
        raise RuntimeError('네이버 지도 설치 버전 확인 실패')
    enabled = _pc_required(serial, ['shell', 'pm', 'list', 'packages', '-e', '--user', '0', NMAP_PACKAGE])
    if 'package:' + NMAP_PACKAGE not in enabled.stdout.splitlines():
        raise RuntimeError('네이버 지도 활성 상태 확인 실패')
    logging.info('[%s] 네이버 지도 설치·버전·활성 상태 확인 완료', serial)


# ============================================================
# 4. 메인 프로세스
# ============================================================

def verify_final_reset(serial):
    errors = []
    _TLS.accounts_clear = False
    _TLS.user_apps_clear = False
    try:
        verify_non_samsung_accounts_removed(serial)
        _TLS.accounts_clear = True
    except DeviceLostError:
        raise
    except Exception as e:
        errors.append(str(e))
    try:
        listed = _pc_required(serial, ['shell', 'pm', 'list', 'packages', '--user', '0', '-3'])
        rows = [line.strip() for line in listed.stdout.splitlines() if line.strip()]
        if any(not re.fullmatch(r'package:[A-Za-z0-9_.]+', row) for row in rows):
            raise RuntimeError('최종 앱 목록 조회 형식 확인 실패')
        packages = [row[8:] for row in rows]
        remaining = sorted(set(packages) - KEEP_APPS)
        if remaining:
            errors.append('삭제 대상 앱 잔존: ' + ', '.join(remaining))
        else:
            _TLS.user_apps_clear = True
    except DeviceLostError:
        raise
    except Exception as e:
        errors.append(str(e))
    apps = [load_nmap_bundle(), load_filemanager_bundle(), *load_phone_bundle().values()]
    for app in apps:
        try:
            path = _pc_required(serial, ['shell', 'pm', 'path', '--user', '0', app['package']])
            if not path.stdout.strip().startswith('package:') or _package_version(serial, app['package']) < app['versionCode']:
                raise RuntimeError('필수 앱 설치·버전 확인 실패: ' + app['package'])
        except DeviceLostError:
            raise
        except Exception as e:
            errors.append(str(e))
    if errors:
        raise RuntimeError('; '.join(errors))


def prepare_wireless_separately(serial):
    try:
        prepare_wireless_reset(serial)
    except DeviceLostError:
        raise
    except Exception as e:
        add_post_run_notice('[%s] 무선 초기화 준비 실패 (초기화 결과와 별도): %s' % (serial, e))
        logging.warning('[%s] 무선 준비 실패 · 최근 앱 정리 계속: %s', serial, e)


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
        ('사용자 설치 앱 삭제', lambda: delete_user_installed_apps(serial)),
        ('계정 삭제', lambda: remove_non_samsung_accounts(serial)),
        ('유지 앱 데이터 초기화', lambda: clear_google_apps_history(serial)),
        ('e-SIM 프로필 삭제', lambda: delete_esim_profiles(serial)),
        ('내부 저장소 초기화', lambda: wipe_internal_storage(serial)),
        ('로그·캐시 정리', lambda: clear_logs_and_cache(serial)),
        ('갤러리 휴지통 정리', lambda: deep_clean_gallery_trash(serial)),
        ('미디어 스토어 갱신', lambda: clear_media_store(serial)),
        ('썸네일 잔여물 제거', lambda: clear_thumbnails()),
        ('언어 설정', lambda: set_device_language(serial, locale)),
        ('카메라 설정 초기화', lambda: reset_camera_settings(serial)),
        ('내비게이션 바 초기화', lambda: reset_navigation_bar(serial)),
        ('글자 크기 초기화', lambda: reset_font_settings(serial)),
        ('필수 앱 설치·버전 확인', lambda: ensure_essential_apps_installed(serial)),
        ('홈 독 앱 배치', lambda: ensure_hotseat_apps(serial)),
        ('런처 초기화', lambda: reset_launcher_with_clean_home(serial)),
        ('배경화면 적용', lambda: push_default_wallpaper(serial, wallpaper, series)),
        ('최종 검증', lambda: verify_final_reset(serial)),
        ('최근 앱 목록 제거', lambda: clear_recent_tasks(serial)),
    ]


# 단계를 추가/삭제해도 진행률 분모가 자동으로 따라가도록 실제 목록에서 센다
PIPELINE_STEP_COUNT = len(build_pipeline('', None, '', ''))


def configure_screen_timeout(serial):
    """초기화 전에 소유자 화면 꺼짐 시간을 10분으로 설정하고 유지합니다."""
    try:
        for args in (['put', 'system', 'screen_off_timeout', '600000'],
                     ['get', 'system', 'screen_off_timeout']):
            result = run_command(['adb', '-s', serial, 'shell', 'settings', '--user', '0'] + args, timeout=15)
            if result.returncode != 0 or (result.stderr or '').strip():
                raise RuntimeError('설정 명령 실패')
        if result.stdout.strip() != '600000':
            raise RuntimeError('설정값 불일치')
        logging.info('[%s] 화면 꺼짐 시간 10분 설정 확인', serial)
    except DeviceLostError:
        raise
    except Exception:
        message = '[%s] 화면 꺼짐 시간 10분 적용 확인 필요 · 초기화는 계속 진행합니다.' % serial
        logging.warning(message)
        add_post_run_notice(message)


def process_device(serial, locale=None):
    """단일 기기에 대한 전체 초기화 프로세스를 실행합니다."""
    _TLS.serial = serial  # 이 워커 스레드의 로그를 기기별 버퍼로 분류하기 위한 태깅
    logging.info('========================================')
    logging.info('[%s] 초기화 시작', serial)
    logging.info('========================================')

    current_label = '연결·소유자 확인'
    _TLS.cleared = set()
    errors = {}
    try:
        if _pc_required(serial, ['shell', 'am', 'get-current-user']).stdout.strip() != '0':
            raise RestorationError('소유자(사용자 0) 화면에서만 초기화할 수 있습니다.')
        current_label = '화면 꺼짐 시간 설정'
        configure_screen_timeout(serial)
        current_label = '모델 확인'
        # 모델명은 한 번만 조회해 시리즈 판정과 화면 표시에 함께 쓴다
        model = get_device_model(serial)
        series = series_from_model(model, serial)
        wallpaper = f'{series}.png'
        if PROGRESS:
            PROGRESS.set_info(serial, model, series, get_device_name(serial), locale or '')

        steps = build_pipeline(serial, locale, wallpaper, series)
        for number, (label, action) in enumerate(steps, start=1):
            current_label = label
            if PROGRESS:
                PROGRESS.step(serial, number - 1, label)
            logging.info('[%s] (%d/%d) %s', serial, number, len(steps), label)
            if label == '최근 앱 목록 제거':
                prepare_wireless_separately(serial)
            try:
                action()
            except (DeviceLostError, RestorationError):
                raise
            except Exception as e:
                errors[label] = str(e)
                logging.error('[%s] %s 실패 · 다음 단계 계속: %s', serial, label, e)
            if PROGRESS:
                PROGRESS.step(serial, number, label)
        current_label = '최종 계정 확인'
        try:
            verify_non_samsung_accounts_removed(serial)
            _TLS.accounts_clear = True
        except DeviceLostError:
            raise
        except Exception as e:
            errors[current_label] = str(e)
        if getattr(_TLS, 'accounts_clear', False):
            errors.pop('계정 삭제', None)
        if getattr(_TLS, 'user_apps_clear', False):
            errors.pop('사용자 설치 앱 삭제', None)
        if errors:
            current_label = '최종 결과'
            raise RuntimeError('; '.join('%s: %s' % item for item in errors.items()))
    except Exception as e:
        # 예외는 main()이 기기별로 잡아 기록하고 해당 기기만 중단 (기존 동작 유지).
        # 단계명을 붙여 다시 던져 실패 사유에 이탈 시점이 남게 한다.
        logging.error('[%s] %s 중 실패: %s', serial, current_label, e)
        if PROGRESS:
            PROGRESS.fail(serial, '%s 중 실패: %s' % (current_label, e))
        raise RuntimeError('%s 중 실패: %s' % (current_label, e)) from e
    finally:
        _TLS.serial = None
        _TLS.cleared = None
        _TLS.accounts_clear = False
        _TLS.user_apps_clear = False

    if PROGRESS:
        PROGRESS.done(serial)
    logging.info('========================================')
    logging.info('[%s] 초기화 완료', serial)
    logging.info('========================================')


# 동시 처리 대수 상한 — 모든 명령이 adb 서버 하나를 거치므로 무제한 병렬은
# 명령 지연·타임아웃을 키운다. 8대씩이면 20대는 3차례로 안정 처리.
MAX_WORKERS = 8

# 실행 종료 후 콘솔에 출력할 공지 (예: e-SIM 수동 삭제 필요)
POST_RUN_NOTICES = []
_NOTICE_LOCK = threading.Lock()


def add_post_run_notice(message):
    with _NOTICE_LOCK:
        POST_RUN_NOTICES.append(message)


def wait_for_ready_devices():
    """기기를 스캔하고, 문제 상태 기기가 있으면 표로 보여준 뒤 재스캔 기회를 줍니다.

    반환: 초기화 가능한 시리얼 목록 (빈 목록이면 호출부에서 재시도).
    """
    while True:
        ready, problems = scan_devices()
        if problems:
            print()
            print(_c('⚠ 연결됐지만 초기화할 수 없는 기기 %d대:' % len(problems), _C_YELLOW))
            print(_c('─' * 62, _C_DIM))
            for serial, state in problems:
                hint = DEVICE_STATE_HINTS.get(state, '상태 확인 필요: %s' % state)
                print('  %-18s ' % serial + _c('%-13s' % state, _C_YELLOW) + ' ' + hint)
            print(_c('─' * 62, _C_DIM))
            choice = input(_c('[r]', _C_CYAN) + ' 다시 스캔  ' + _c('[Enter]', _C_CYAN) +
                           ' 정상 %d대만 진행: ' % len(ready)).strip().lower()
            if choice == 'r':
                continue
        return ready


def run_fleet(devices, locales):
    """기기 목록을 병렬 초기화하고 {serial: 실패사유}를 반환합니다.

    locales: 전 기기 공통 locale 문자열(또는 None), 혹은 {serial: locale} 배정표.
    """
    def locale_for(serial):
        return locales.get(serial) if isinstance(locales, dict) else locales

    global PROGRESS
    PROGRESS = ProgressConsole(PIPELINE_STEP_COUNT)
    if not PROGRESS.start(devices):
        PROGRESS = None  # 콘솔이 아니면 기존 로그 방식으로 진행

    failures = {}
    executor = ThreadPoolExecutor(max_workers=min(MAX_WORKERS, len(devices)))
    try:
        futures = {executor.submit(process_device, serial, locale_for(serial)): serial
                   for serial in devices}
        # as_completed 무한 대기 중에는 플랫폼에 따라 Ctrl+C(SIGINT) 처리가
        # 대기가 끝날 때까지 지연된다 — 0.5초 폴링으로 즉시 반응하게 한다.
        pending = set(futures)
        while pending:
            done, pending = futures_wait(pending, timeout=0.5)
            for future in done:
                serial = futures[future]
                try:
                    future.result()
                except Exception as e:
                    failures[serial] = str(e)
                    logging.error('[%s] 초기화 중 예외 발생: %s', serial, e)
        executor.shutdown(wait=True)
    except KeyboardInterrupt:
        # 진행 중 단계는 마저 돌지만 대기 중이던 기기는 시작하지 않고 즉시 빠져나온다
        executor.shutdown(wait=False, cancel_futures=True)
        for future, serial in futures.items():
            if serial not in failures and not future.done():
                failures[serial] = '사용자 중단'
        raise
    finally:
        if PROGRESS:
            PROGRESS.stop()
            PROGRESS = None
    return failures


def report_run(devices, failures, log_buffer, activity='초기화'):
    """실행 결과 요약 + 실패 기기의 직전 로그를 출력합니다."""
    logging.info('모든 기기 %s 작업이 종료되었습니다.', activity)
    with _NOTICE_LOCK:
        notices, POST_RUN_NOTICES[:] = list(POST_RUN_NOTICES), []
    for notice in notices:
        print('⚠ %s' % notice)
    if not failures:
        return
    logging.error('실패한 기기 %d대: %s — 해당 작업을 다시 시도하세요.',
                  len(failures), ', '.join(failures))
    for serial, reason in failures.items():
        print()
        print('──── [%s] 실패: %s ────' % (serial, reason))
        for line in log_buffer.tail(serial):
            print('  ' + line)


def main():
    load_phone_bundle(verify_hash=True)
    load_nmap_bundle(verify_hash=True)
    load_filemanager_bundle(verify_hash=True)
    # 메뉴·표의 색상 표시 (진행률 화면과 동일한 VT 경로, 미지원 콘솔이면 무색)
    global ANSI_OK
    ANSI_OK = sys.stdout.isatty() and _enable_ansi()

    log_buffer = DeviceLogHandler()
    logging.getLogger().addHandler(log_buffer)

    while True:
        devices = wait_for_ready_devices()
        if not devices:
            logging.error('연결된 기기가 없습니다. ADB 연결을 확인해주세요.')
        else:
            logging.info('총 연결된 기기 수: %d', len(devices))
            for device in devices:
                logging.info(' - %s', device)

            # [V6] 언어 선택 메뉴
            locales = assign_languages(devices)

            failures = run_fleet(devices, locales)
            report_run(devices, failures, log_buffer)

            # 기기 종료 여부 확인 — 실패한 기기는 켜진 채 남겨 물리적으로 구분되게 한다
            shutdown = input('기기를 종료하시겠습니까? (y/n): ').strip().lower()
            if shutdown == 'y':
                survivors = [serial for serial in devices if serial not in failures]
                for serial in survivors:
                    logging.info('[%s] 기기 종료 중...', serial)
                    run_command(['adb', '-s', serial, 'shell', 'reboot', '-p'])
                logging.info('기기 %d대 종료 명령 전송 완료', len(survivors))
                if failures:
                    print('⚠ 전원이 꺼지지 않은 기기 (실패 — 재작업 필요): %s'
                          % ', '.join(failures))

        try:
            logging.info('추가로 작업하실 기기를 연결 완료 후 엔터를 눌러주세요. (종료하려면 Ctrl+C)')
            input()
        except KeyboardInterrupt:
            logging.info('프로그램을 종료합니다.')
            break


def prepare_phone_only_main():
    parser = argparse.ArgumentParser(description='데이터 삭제 없이 필수 앱 설치와 무선 초기화 준비만 실행')
    parser.add_argument('--prepare-phone-only', action='store_true')
    parser.add_argument('--serial', action='append', help='대상 USB 시리얼, 여러 번 지정 가능')
    args = parser.parse_args()
    load_phone_bundle(verify_hash=True)
    load_nmap_bundle(verify_hash=True)
    load_filemanager_bundle(verify_hash=True)
    devices = args.serial or get_connected_devices()
    if not devices:
        raise RuntimeError('준비할 USB 기기가 없습니다.')
    failures = {}
    buffer = DeviceLogHandler()
    logging.getLogger().addHandler(buffer)
    for serial in devices:
        try:
            ensure_essential_apps_installed(serial)
            prepare_wireless_reset(serial)
        except Exception as error:
            failures[serial] = str(error)
    report_run(devices, failures, buffer, activity='폰 앱 준비 (데이터 삭제 없음)')
    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    if '--prepare-phone-only' in sys.argv:
        prepare_phone_only_main()
    else:
        main()
