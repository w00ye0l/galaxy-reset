"""First USB setup for the phone's own ADB identity. Never logs pairing codes.

Uses the already-authorized USB shell and Android's normal pairing-code dialog.
Does not copy host/Shizuku private keys or edit Android's authorization database.
"""
import re
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
    labels = {label.casefold() for label in labels}
    return next((node for node in root.iter('node') if node.get('text', '').strip().casefold() in labels
                 and node.get('package') in packages), None)


def network_allow_node(root):
    title = ' '.join(n.get('text', '') for n in root.iter('node')).casefold()
    if not any(text in title for text in ('무선 디버깅을 허용', 'ワイヤレス デバッグを許可',
                                         'allow wireless debugging', '允许无线调试', '允許無線')):
        return None
    return node_with_text(root, ALLOW, ('com.android.systemui', 'android', 'com.android.settings'))


def pairing_details(root):
    nodes = [node for node in root.iter('node') if node.get('package') == 'com.android.settings']
    code = next((node.get('text', '').strip() for node in nodes
                 if node.get('resource-id') == 'com.android.settings:id/pairing_code'
                 and re.fullmatch(r'[0-9]{6}', node.get('text', '').strip())), None)
    texts = [node.get('text', '').strip() for node in nodes if node.get('resource-id') == 'com.android.settings:id/ip_addr']
    endpoint = next((re.fullmatch(r'(?:[0-9]{1,3}\.){3}[0-9]{1,3}:([0-9]{1,5})', text) for text in texts
                     if re.fullmatch(r'(?:[0-9]{1,3}\.){3}[0-9]{1,3}:([0-9]{1,5})', text)), None)
    if code is None or endpoint is None or not 1 <= int(endpoint[1]) <= 65535:
        raise RuntimeError('시스템 페어링 코드 창을 확인하지 못했습니다.')
    return int(endpoint[1]), code


def prepare(serial, execute):
    def run(*args, timeout=30):
        # Raw execution deliberately avoids generic command logging (the broadcast contains an OTP).
        try:
            command = ['adb', '-s', serial, *args]
            try:
                result = execute(command, timeout)
            except subprocess.TimeoutExpired:
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
            return ET.fromstring(xml[xml.index('<?xml'):])
        except (ValueError, ET.ParseError):
            raise RuntimeError('기기 페어링 화면을 읽을 수 없습니다. 잠금을 풀고 다시 실행하세요.') from None

    def tap(node):
        run('shell', 'input', 'tap', *center(node))

    code_dialog = False
    try:
        run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        run('shell', 'cmd', 'statusbar', 'collapse')
        run('shell', 'am', 'start', '-a', 'android.settings.APPLICATION_DEVELOPMENT_SETTINGS')
        root = tree()
        allow = network_allow_node(root)
        if allow is not None:
            tap(allow); root = tree()
        for attempt in range(9):
            if not any(n.get('package') == 'com.android.settings' for n in root.iter('node')):
                raise RuntimeError('개발자 옵션 화면이 아닙니다. 기기 잠금을 직접 해제해주세요.')
            target = node_with_text(root, WIRELESS)
            if target is not None:
                tap(target)
                break
            if attempt == 8:
                raise RuntimeError('무선 디버깅 메뉴를 찾지 못했습니다. 잠금 해제와 개발자 옵션을 확인하세요.')
            rectangles = [list(map(int, re.findall(r'\d+', n.get('bounds', '')))) for n in root.iter('node')]
            rectangles = [r for r in rectangles if len(r) == 4]
            width = max(r[2] for r in rectangles); height = max(r[3] for r in rectangles)
            run('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 4), '350')
            root = tree()
        root = tree()
        target = node_with_text(root, PAIR)
        if target is None:
            switches = [n for n in root.iter('node') if n.get('class') == 'android.widget.Switch'
                        and n.get('package') == 'com.android.settings' and n.get('checked') == 'false']
            if switches:
                tap(switches[0]); root = tree()
            # One-time network permission only. Never checks "always allow" automatically.
            allow = network_allow_node(root)
            if allow is not None:
                tap(allow); root = tree()
            target = node_with_text(root, PAIR)
        if target is None:
            raise RuntimeError('무선 디버깅을 켜고 현재 네트워크를 허용해주세요.')
        tap(target); code_dialog = True
        port, code = pairing_details(tree())
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
