"""First USB setup for the phone's own ADB identity. Never logs pairing codes.

Uses the already-authorized USB shell and Android's normal pairing-code dialog.
Does not copy host/Shizuku private keys or edit Android's authorization database.
"""
import re
import ipaddress
import subprocess
import time
import xml.etree.ElementTree as ET

WIRELESS = {'무선 디버깅', 'ワイヤレス デバッグ', 'Wireless debugging', '无线调试', '無線偵錯', '無線調試'}
PAIR = {'페어링 코드로 기기 페어링', '페어링 코드로 기기 연결', '専用コードによるデバイスのペア設定',
        'Pair device with pairing code', '使用配对码配对设备', '使用配對碼配對裝置', '使用配對碼配對設備'}
ALLOW = {'허용', '許可', 'Allow', '允许', '允許'}
PACKAGE = 'com.forholiday.galaxyreset'
UI_FILE = '/data/local/tmp/galaxy-reset-pair.xml'


def center(node):
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not bounds:
        raise RuntimeError('페어링 화면의 버튼 위치를 확인할 수 없습니다.')
    left, top, right, bottom = map(int, bounds.groups())
    if not (0 <= left < right <= 10000 and 0 <= top < bottom <= 10000):
        raise RuntimeError('페어링 버튼이 화면에 표시되지 않았습니다.')
    return str((left + right) // 2), str((top + bottom) // 2)


def node_with_text(root, labels, packages=('com.android.settings', 'android')):
    labels = {re.sub(r'\s+', '', label).casefold() for label in labels}
    return next((node for node in root.iter('node') if re.sub(r'\s+', '', node.get('text', '')).casefold() in labels
                 and node.get('package') in packages and node.get('enabled', 'true') != 'false'), None)


def network_allow_node(root):
    title = re.sub(r'\s+', '', ' '.join(n.get('text', '') for n in root.iter('node'))).casefold()
    if not any(re.sub(r'\s+', '', text) in title for text in ('무선 디버깅을 허용', 'ワイヤレス デバッグを許可',
                                         'allow wireless debugging', '允许无线调试', '允許無線')):
        return None
    return node_with_text(root, ALLOW, ('com.android.systemui', 'android', 'com.android.settings'))


def pairing_details(root):
    nodes = [node for node in root.iter('node') if node.get('package') == 'com.android.settings']
    code = next((re.sub(r"\s+", "", node.get('text', '')) for node in nodes
                 if node.get('resource-id') == 'com.android.settings:id/pairing_code'
                 and re.fullmatch(r'[0-9]{6}', re.sub(r"\s+", "", node.get('text', '')))), None)
    port = None
    for node in nodes:
        if node.get('resource-id') != 'com.android.settings:id/ip_addr':
            continue
        try:
            host, value = node.get('text', '').strip().rsplit(':', 1)
            ipaddress.ip_address(host.strip('[]').split('%')[0])
            if value.isdigit() and 1 <= int(value) <= 65535:
                port = int(value)
        except ValueError:
            continue
    if code is None or port is None:
        raise RuntimeError('시스템 페어링 코드 창을 확인하지 못했습니다.')
    return port, code


def prepare(serial, execute):
    def run(*args, timeout=30):
        # Raw execution deliberately avoids generic command logging (the broadcast contains an OTP).
        try:
            command = ['adb', '-s', serial, *args]
            try:
                result = execute(command, timeout)
            except subprocess.TimeoutExpired:
                # Never replay a tap, switch toggle or pairing broadcast after an unknown outcome.
                if args[:3] == ('shell', 'input', 'tap') or any('PAIR_FROM_USB' in a for a in args):
                    raise
                result = execute(command, timeout)
        except Exception:
            raise RuntimeError('자동 시작 페어링 명령이 중단됐습니다. USB 연결과 잠금 해제를 확인하세요.') from None
        if result.returncode:
            raise RuntimeError('자동 시작 페어링 명령이 실패했습니다. 기기 화면과 USB 연결을 확인하세요.')
        return result.stdout or ''

    def tree():
        run('shell', 'uiautomator', 'dump', UI_FILE, timeout=15)
        xml = run('exec-out', 'cat', UI_FILE, timeout=10)
        try:
            if len(xml) > 2 * 1024 * 1024 or '<!DOCTYPE' in xml or '<!ENTITY' in xml:
                raise ValueError('Invalid hierarchy')
            return ET.fromstring(xml)
        except (ValueError, ET.ParseError):
            raise RuntimeError('기기 페어링 화면을 읽을 수 없습니다. 잠금을 풀고 다시 실행하세요.') from None

    def tap(node):
        run('shell', 'input', 'tap', *center(node))

    def verify_permission():
        # The initial USB operator already authorized this setup. Approve only this app's own Shizuku request.
        try:
            for _ in range(5):
                root = tree()
                if node_with_text(root, {'권한 연결됨'}, (PACKAGE,)) is not None:
                    return
                request = node_with_text(root, {'권한 허용하기'}, (PACKAGE,))
                if request is not None:
                    tap(request); continue
                labels = ' '.join(n.get('text', '') for n in root.iter('node'))
                allow = node_with_text(root, {'Allow all the time', '항상 허용', '常に許可', '始终允许', '一律允許', '一律允許'},
                                       ('moe.shizuku.privileged.api',))
                if allow is not None and '갤럭시 초기화' in labels:
                    tap(allow); continue
                time.sleep(1)
            raise RuntimeError('자동 시작 인증은 등록됐으나 Shizuku 앱 권한 연결을 확인하지 못했습니다. 초기화 앱 화면을 확인하세요.')
        finally:
            try:
                run('shell', 'rm', '-f', UI_FILE)
            except RuntimeError:
                pass
    # Verify the phone app's actual ADB identity, not merely the Shizuku process or a saved flag.
    auth = run('shell', 'am', 'broadcast', '-n', PACKAGE + '/.AdbBootstrapReceiver',
               '-a', PACKAGE + '.CHECK_ADB_AUTH_FROM_USB', timeout=25)
    if re.search(r'Broadcast completed: result=0\b', auth) and 'AUTHENTICATED' in auth:
        run('shell', 'am', 'start', '-f', '0x10008000', '-n', PACKAGE + '/.MainActivity')
        verify_permission()
        return
    code_dialog = False
    try:
        run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        run('shell', 'cmd', 'statusbar', 'collapse')
        run('shell', 'am', 'start', '-W', '--user', '0', '-a', 'android.settings.APPLICATION_DEVELOPMENT_SETTINGS')
        root = tree()
        allow = network_allow_node(root)
        if allow is not None:
            tap(allow); root = tree()
        def safe_menu(labels):
            nonlocal root
            for attempt in range(12):
                allow = network_allow_node(root)
                if allow is not None:
                    tap(allow); root = tree(); continue
                if not any(n.get('package') == 'com.android.settings' for n in root.iter('node')):
                    raise RuntimeError('설정 메뉴 진입 실패 · 잠금 해제와 화면 상태를 확인하세요.')
                target = node_with_text(root, labels)
                rectangles = [list(map(int, re.findall(r'\d+', n.get('bounds', '')))) for n in root.iter('node')]
                rectangles = [r for r in rectangles if len(r) == 4]
                if not rectangles:
                    raise RuntimeError('설정 화면 크기를 확인하지 못했습니다.')
                width = max(r[2] for r in rectangles); height = max(r[3] for r in rectangles)
                if target is not None:
                    left, top, right, bottom = map(int, re.findall(r'\d+', target.get('bounds', '')))
                    # Samsung can report text bounds underneath the system navigation bar.
                    # Bring the entire row to the middle before tapping, never into Recents.
                    if height * .15 <= top < bottom <= height * .78:
                        tap(target); root = tree(); return
                    down = top < height * .15
                else:
                    down = False
                if attempt == 11:
                    raise RuntimeError('설정 메뉴를 안전한 화면 영역에서 찾지 못했습니다.')
                start, end = (.38, .65) if down else (.68, .35)
                run('shell', 'input', 'swipe', str(width // 2), str(int(height * start)),
                    str(width // 2), str(int(height * end)), '350')
                root = tree()
        pair_labels = {re.sub(r'\s+', '', text).casefold() for text in PAIR}
        if not any(n.get('package') == 'com.android.settings' and re.sub(r'\s+', '', n.get('text', '')).casefold() in pair_labels for n in root.iter('node')):
            safe_menu(WIRELESS)
        restarted = False
        for attempt in range(10):
            allow = network_allow_node(root)
            if allow is not None:
                tap(allow); root = tree(); continue
            if node_with_text(root, PAIR) is not None:
                break
            switches = [n for n in root.iter('node')
                        if n.get('class', '').endswith(('Switch', 'SwitchCompat'))
                        and n.get('package') == 'com.android.settings'
                        and n.get('enabled', 'true') == 'true' and n.get('checked') == 'false']
            if len(switches) == 1:
                tap(switches[0])
            else:
                enabled_switches = [n for n in root.iter('node') if n.get('class', '').endswith(('Switch', 'SwitchCompat'))
                                    and n.get('package') == 'com.android.settings' and n.get('checked') == 'true'
                                    and n.get('enabled', 'true') == 'true']
                pair_labels = {re.sub(r'\s+', '', text).casefold() for text in PAIR}
                on_wireless_page = any(n.get('package') == 'com.android.settings' and
                    re.sub(r'\s+', '', n.get('text', '')).casefold() in pair_labels for n in root.iter('node'))
                if attempt >= 2 and not restarted and on_wireless_page and len(enabled_switches) == 1:
                    # Some Samsung builds leave the switch ON while the TLS service/menu is inactive.
                    # Restart through its normal UI once; the next pass turns it on and handles consent.
                    tap(enabled_switches[0]); restarted = True
                else:
                    time.sleep(.3)
            root = tree()
        else:
            raise RuntimeError('무선 디버깅 준비 실패 · Wi-Fi 연결과 현재 네트워크 허용을 확인하세요.')
        safe_menu(PAIR); code_dialog = True
        for attempt in range(8):
            try:
                port, code = pairing_details(root)
                break
            except RuntimeError:
                if attempt == 7:
                    raise
                time.sleep(.3); root = tree()
        response = run('shell', 'am', 'broadcast', '--receiver-foreground', '-n', PACKAGE + '/.AdbBootstrapReceiver',
                       '-a', PACKAGE + '.PAIR_FROM_USB', '--ei', 'port', str(port), '--es', 'code', code, timeout=40)
        if not re.search(r'Broadcast completed: result=0\b', response) or 'PAIRED' not in response:
            raise RuntimeError('초기화 앱의 자동 시작 인증 등록을 확인하지 못했습니다.')
    finally:
        # OTPs are never persisted on the PC; remove the temporary device hierarchy as well.
        try:
            if code_dialog:
                run('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            run('shell', 'rm', '-f', UI_FILE)
            run('shell', 'am', 'start', '-f', '0x10008000', '-n', PACKAGE + '/.MainActivity')
        except RuntimeError:
            pass
    verify_permission()
