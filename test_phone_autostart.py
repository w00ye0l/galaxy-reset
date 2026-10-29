"""USB bootstrap UI tests. No real ADB invocation or customer data access."""
import subprocess
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as E
from xml.sax.saxutils import escape
import phone_autostart as setup


def screen(*texts, package='com.android.settings'):
    return '<?xml version="1.0"?><hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">' + ''.join(
        '<node package="%s" text="%s" resource-id="%s" enabled="true" bounds="[100,800][900,900]" />' %
        (package, escape(t), 'com.android.settings:id/pairing_code' if t == '123456' else 'com.android.settings:id/ip_addr' if ':' in t else '') for t in texts) + '</node></hierarchy>'


class Fake:
    def __init__(self):
        self.screen = 0
        self.calls = []
        self.fail_pair = False
        self.locked = False

    def run(self, command, timeout):
        self.calls.append(command)
        if command[3:5] == ['exec-out', 'cat']:
            xml = screen('ロック画面', package='com.android.systemui') if self.locked else [
                screen('ワイヤレス デバッグ'), screen('専用コードによるデバイスのペア設定'), screen('123456', '172.30.1.93:34567'), screen('권한 연결됨', package=setup.PACKAGE)][self.screen]
            return subprocess.CompletedProcess(command, 0, xml, '')
        if command[3:6] == ['shell', 'input', 'tap']:
            self.screen += 1
        if command[-1] == setup.PACKAGE + '/.MainActivity':
            self.screen = 3
        if setup.PACKAGE + '.PAIR_FROM_USB' in command:
            return subprocess.CompletedProcess(command, 0, 'Broadcast completed: result=1, data="PAIR_FAILED"' if self.fail_pair else 'Broadcast completed: result=0, data="PAIRED"', '')
        return subprocess.CompletedProcess(command, 0, '', '')


class PhoneAutostartTest(unittest.TestCase):
    def test_clipped_wireless_row_is_scrolled_away_from_navigation_bar(self):
        class Clipped(Fake):
            scrolled = False
            def run(self, command, timeout):
                if command[3:6] == ['shell', 'input', 'swipe']:
                    self.scrolled = True
                if command[3:5] == ['exec-out', 'cat'] and self.screen == 0 and not self.scrolled:
                    self.calls.append(command)
                    return subprocess.CompletedProcess(command, 0, screen('ワイヤレス デバッグ').replace('[100,800][900,900]', '[100,2190][900,2261]'), '')
                return super().run(command, timeout)
        fake=Clipped();setup.prepare('TEST',fake.run)
        self.assertTrue(fake.scrolled)
        self.assertFalse(any(c[3:6] == ['shell','input','tap'] and c[-1] == '2225' for c in fake.calls))

    def test_disabled_pair_row_does_not_skip_enabling_wireless(self):
        class Off(Fake):
            off = True
            def run(self, command, timeout):
                if self.screen == 1 and self.off and command[3:5] == ['exec-out','cat']:
                    self.calls.append(command)
                    xml=screen('専用コードによるデバイスのペア設定').replace('enabled="true"','enabled="false"')
                    xml=xml.replace('</hierarchy>','<node package="com.android.settings" class="android.widget.Switch" checked="false" enabled="true" bounds="[100,400][900,500]" /></hierarchy>')
                    return subprocess.CompletedProcess(command,0,xml,'')
                if self.screen == 1 and self.off and command[3:6] == ['shell','input','tap']:
                    self.calls.append(command);self.off=False
                    return subprocess.CompletedProcess(command,0,'','')
                return super().run(command,timeout)
        fake=Off();setup.prepare('TEST',fake.run);self.assertFalse(fake.off)

    def test_valid_saved_identity_skips_pairing_ui(self):
        class Authenticated(Fake):
            def run(self, command, timeout):
                if setup.PACKAGE+'.CHECK_ADB_AUTH_FROM_USB' in command:
                    self.calls.append(command)
                    return subprocess.CompletedProcess(command,0,'Broadcast completed: result=0, data="AUTHENTICATED"','')
                return super().run(command,timeout)
        fake=Authenticated();setup.prepare('TEST',fake.run)
        self.assertFalse(any(setup.PACKAGE+'.PAIR_FROM_USB' in c for c in fake.calls))
        self.assertFalse(any('android.settings.APPLICATION_DEVELOPMENT_SETTINGS' in c for c in fake.calls))

    def test_unknown_pairing_timeout_is_never_replayed_or_leaked(self):
        class Timeout(Fake):
            attempts = 0
            def run(self, command, timeout):
                if setup.PACKAGE+'.PAIR_FROM_USB' in command:
                    self.attempts+=1;raise subprocess.TimeoutExpired(command,timeout)
                return super().run(command,timeout)
        fake=Timeout()
        with self.assertRaises(RuntimeError) as caught:setup.prepare('TEST',fake.run)
        self.assertEqual(fake.attempts,1);self.assertNotIn('123456',str(caught.exception))

    def test_ipv6_and_spaced_pair_code_are_supported(self):
        xml=screen('123456','[fe80::1234%wlan0]:34567').replace('text="123456"','text="123 456"')
        self.assertEqual((34567,'123456'),setup.pairing_details(E.fromstring(xml)))

    def test_first_shizuku_request_is_approved_only_for_our_named_app(self):
        class PermissionFake(Fake):
            def run(self, command, timeout):
                if command[3:5] == ['exec-out', 'cat'] and self.screen >= 3:
                    self.calls.append(command)
                    xml = {3: screen('권한 허용하기', package=setup.PACKAGE),
                           4: screen('갤럭시 초기화', '常に許可', package='moe.shizuku.privileged.api'),
                           5: screen('권한 연결됨', package=setup.PACKAGE)}[self.screen]
                    return subprocess.CompletedProcess(command, 0, xml, '')
                return super().run(command, timeout)
        fake = PermissionFake(); setup.prepare('TEST', fake.run)
        self.assertEqual(5, fake.screen)

    def test_another_apps_shizuku_request_is_never_approved(self):
        class OtherAppFake(Fake):
            def run(self, command, timeout):
                if command[3:5] == ['exec-out', 'cat'] and self.screen >= 3:
                    self.calls.append(command)
                    return subprocess.CompletedProcess(command, 0, screen('다른 앱', '常に許可', package='moe.shizuku.privileged.api'), '')
                return super().run(command, timeout)
        fake = OtherAppFake()
        with patch.object(setup.time, 'sleep'), self.assertRaisesRegex(RuntimeError, '권한 연결'):
            setup.prepare('TEST', fake.run)
        self.assertEqual(3, fake.screen)

    def test_usb_bootstrap_delivers_code_only_to_protected_app_receiver(self):
        fake = Fake(); setup.prepare('TEST', fake.run)
        pair = next(c for c in fake.calls if setup.PACKAGE + '.PAIR_FROM_USB' in c)
        self.assertEqual(pair[:3], ['adb', '-s', 'TEST'])
        self.assertIn(setup.PACKAGE + '/.AdbBootstrapReceiver', pair)
        self.assertEqual(pair[-1], '123456')
        self.assertFalse(any('pair' == x or 'adb_keys' in x or 'always' in x.lower() for c in fake.calls for x in c))
        self.assertTrue(any(c[3:6] == ['shell', 'rm', '-f'] for c in fake.calls))

    def test_locked_phone_is_not_swiped_or_paired(self):
        fake = Fake(); fake.locked = True
        with self.assertRaisesRegex(RuntimeError, '잠금'):
            setup.prepare('TEST', fake.run)
        self.assertFalse(any('swipe' in c or setup.PACKAGE + '.PAIR_FROM_USB' in c for c in fake.calls))

    def test_failed_registration_is_not_reported_as_ready_or_leaked_in_error(self):
        fake = Fake(); fake.fail_pair = True
        with self.assertRaises(RuntimeError) as caught:
            setup.prepare('TEST', fake.run)
        self.assertNotIn('123456', str(caught.exception))
        self.assertTrue(any(c[3:6] == ['shell', 'rm', '-f'] for c in fake.calls))

    def test_unrelated_six_digit_text_is_never_used_as_a_pairing_code(self):
        with self.assertRaises(RuntimeError):
            setup.pairing_details(E.fromstring(screen('123456', '172.30.1.93:34567', package='com.example.other')))

    def test_invalid_pairing_port_is_rejected(self):
        with self.assertRaises(RuntimeError):
            setup.pairing_details(E.fromstring(screen('123456', '172.30.1.93:99999')))

    def test_offscreen_or_malformed_bounds_cannot_be_tapped(self):
        self.assertRaises(RuntimeError, setup.center, E.fromstring('<node bounds="[0,0][0,0]" />'))

    def test_network_permission_is_one_time_and_requires_its_specific_prompt(self):
        root = E.fromstring(screen('このネットワークでワイヤレス デバッグを許可しますか？', '許可', 'このネットワークで常に許可する', package='com.android.systemui'))
        self.assertEqual('許可', setup.network_allow_node(root).get('text'))
        self.assertIsNone(setup.network_allow_node(E.fromstring(screen('他の許可', '許可', package='com.android.systemui'))))


if __name__ == '__main__':
    unittest.main()
