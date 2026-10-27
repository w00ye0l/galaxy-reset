"""PC equivalent of the phone's NAVER account removal fallback.

Uses the normal Samsung Settings removal UI. Never logs account names or tokens,
edits account databases, accepts a lock screen, or deletes a NAVER service account.
"""
import re
import time
import xml.etree.ElementTree as ET

NAVER = 'com.nhn.android.naveraccount'
MAP = 'com.nhn.android.nmap'
SETTINGS = 'com.android.settings'
UI_FILE = '/data/local/tmp/galaxy-reset-pc-naver.xml'
REMOVE = {'계정 삭제', 'アカウントを削除', 'Remove account', '删除账号', '删除帐号',
          '移除账号', '移除帐号', '移除帳戶', '移除帳號', '刪除帳戶', '刪除帳號'}
CONFIRM = {'삭제', '削除', 'Remove', 'Remove account', '删除', '移除', '刪除'}


def parse_accounts(dump):
    """Read current owner accounts only, rejecting empty/partial/failed inventories."""
    users = list(re.finditer(r'(?m)^\s*User UserInfo\{(\d+):', dump))
    if users:
        owner = next((i for i, user in enumerate(users) if user[1] == '0'), None)
        if owner is None:
            raise RuntimeError('소유자 계정 목록을 확인하지 못했습니다.')
        dump = dump[users[owner].end():users[owner + 1].start() if owner + 1 < len(users) else len(dump)]
    counts = re.findall(r'(?m)^\s*Accounts:\s*(\d+)\s*$', dump)
    if len(counts) != 1:
        raise RuntimeError('계정 목록 조회 실패 · 삭제 완료로 처리하지 않습니다.')
    types = []
    for line in dump.splitlines():
        line = line.strip()
        if not line.startswith('Account {'):
            continue
        match = re.search(r',\s*type=([A-Za-z0-9_.-]+)\s*\}$', line)
        if not match:
            raise RuntimeError('계정 목록 형식을 확인하지 못했습니다.')
        types.append(match[1])
    if len(types) != int(counts[0]):
        raise RuntimeError('계정 목록이 불완전합니다 · 삭제 완료로 처리하지 않습니다.')
    return types


def checked(serial, runner, args, timeout=30):
    # Queries may retry transient ADB timeouts; never repeat a destructive UI tap.
    retries = 0 if args[:3] == ['shell', 'input', 'tap'] else 1
    result = runner(['adb', '-s', serial, *args], timeout=timeout, retries=retries)
    if (getattr(result, 'returncode', -1) != 0
            or 'SecurityException' in (getattr(result, 'stdout', '') or '')
            or 'SecurityException' in (getattr(result, 'stderr', '') or '')):
        raise RuntimeError('네이버 계정 제거 명령 실행 실패 · 기기 연결/잠금을 확인하세요.')
    return result.stdout or ''


def accounts(serial, runner):
    return parse_accounts(checked(serial, runner, ['shell', 'dumpsys', 'account']))


def clear_map_session(serial, runner):
    """System account removal alone does not invalidate NAVER Map's local session."""
    packages = checked(serial, runner, ['shell', 'pm', 'list', 'packages', '--user', '0', MAP])
    if 'package:' + MAP not in packages.splitlines():
        return
    result = checked(serial, runner, ['shell', 'pm', 'clear', '--user', '0', MAP], timeout=120)
    if result.strip() != 'Success':
        raise RuntimeError('네이버 지도 로그인·저장 데이터 초기화 실패')


def center(node):
    if node is None or node.get('package') != SETTINGS or node.get('enabled') != 'true':
        raise RuntimeError('네이버 계정 제거 버튼 확인 필요 · 화면을 켜고 잠금을 해제하세요.')
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not match:
        raise RuntimeError('계정 설정 화면 좌표 확인 실패')
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise RuntimeError('계정 설정 버튼이 화면에 없습니다.')
    return (left + right) // 2, (top + bottom) // 2


def find(root, resource, labels):
    return next((node for node in root.iter('node') if node.get('package') == SETTINGS
                 and node.get('resource-id') == resource and node.get('text') in labels), None)


def remove(serial, runner):
    """Remove only NAVER entries, then prove the system inventory decreased."""
    count = accounts(serial, runner).count(NAVER)
    if not count:
        return
    if checked(serial, runner, ['shell', 'am', 'get-current-user']).strip() != '0':
        raise RuntimeError('소유자(사용자 0) 화면에서만 네이버 계정을 제거합니다.')

    def shell(*args, timeout=30):
        return checked(serial, runner, ['shell', *args], timeout)

    def screen():
        shell('uiautomator', 'dump', '--compressed', UI_FILE)
        xml = checked(serial, runner, ['exec-out', 'cat', UI_FILE])
        if len(xml) > 262144 or '<!DOCTYPE' in xml or '<!ENTITY' in xml:
            raise RuntimeError('계정 설정 화면 형식 확인 필요')
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            raise RuntimeError('계정 설정 화면 조회 실패') from None

    def tap(node):
        x, y = center(node)
        shell('input', 'tap', str(x), str(y), timeout=10)
        time.sleep(0.35)

    try:
        for _ in range(10):
            start = shell('am', 'start', '-W', '--user', '0', '-a', 'android.settings.SYNC_SETTINGS')
            if 'Status: ok' not in start:
                raise RuntimeError('계정 관리 화면 실행 실패')
            root = screen()
            row = find(root, 'android:id/summary', {'NAVER'})
            for _ in range(5):
                if row is not None:
                    break
                scroll = next((n for n in root.iter('node') if n.get('package') == SETTINGS
                               and n.get('scrollable') == 'true'), None)
                if scroll is None:
                    break
                x, y = center(scroll)
                shell('input', 'swipe', str(x), str(y + 150), str(x), str(max(0, y - 150)), '300')
                root = screen()
                row = find(root, 'android:id/summary', {'NAVER'})
            tap(row)
            root = screen()
            if find(root, SETTINGS + ':id/entity_header_summary', {'NAVER'}) is None:
                raise RuntimeError('선택된 계정이 NAVER인지 확인하지 못했습니다.')
            tap(find(root, SETTINGS + ':id/button', REMOVE))
            root = screen()
            title = next((n.get('text', '') for n in root.iter('node') if n.get('package') == SETTINGS
                          and n.get('resource-id', '').endswith(':id/alertTitle')), '')
            if not any(label.lower() in title.lower() for label in REMOVE):
                raise RuntimeError('네이버 계정 삭제 확인창을 확인하지 못했습니다.')
            tap(find(root, 'android:id/button1', CONFIRM))
            after = count
            for _ in range(10):
                time.sleep(0.5)
                after = accounts(serial, runner).count(NAVER)
                if after < count:
                    break
            if after >= count:
                raise RuntimeError('네이버 계정 제거가 거부되었거나 확인되지 않았습니다.')
            count = after
            if count == 0:
                return
        raise RuntimeError('네이버 계정이 기기에 남아 있습니다.')
    finally:
        for args in [('rm', '-f', UI_FILE), ('am', 'start', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')]:
            try:
                shell(*args, timeout=15)
            except Exception:
                pass
