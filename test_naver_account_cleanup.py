"""PC account removal regressions. No real device commands are executed."""
import subprocess
import unittest
from unittest.mock import patch, Mock
from xml.sax.saxutils import escape
import V6
import naver_account_cleanup as cleanup


def node(resource, text, package=cleanup.SETTINGS, **attrs):
    fields = dict(package=package, text=text, **{'resource-id': resource},
                  enabled='true', bounds='[10,20][110,120]')
    fields.update(attrs)
    return '<node ' + ' '.join('%s="%s"' % (k, escape(v, {'"': '&quot;'})) for k, v in fields.items()) + '/>'


def page(*nodes):
    return '<?xml version="1.0"?><hierarchy>' + ''.join(nodes) + '</hierarchy>'


class Device:
    def __init__(self):
        self.naver = 1
        self.google = False
        self.screen = 0
        self.calls = []
        self.refuse = self.foreign = self.wrong_dialog = self.timeout = False
        self.broken_inventory = None
        self.user = '0'

    def run(self, command, **kwargs):
        self.calls.append(command)
        args = command[3:]
        out, code = '', 0
        if args == ['shell', 'dumpsys', 'account']:
            out = ('User UserInfo{0:Owner:13}:\nAccounts: %d\n' % (1 + self.naver + int(self.google))
                   + 'Account {name=private-samsung, type=com.osp.app.signin}\n'
                   + 'Account {name=private-naver, type=com.nhn.android.naveraccount}\n' * self.naver
                   + ('Account {name=private-google, type=com.google}\n' if self.google else ''))
            if self.broken_inventory is not None:
                out = self.broken_inventory
        elif args == ['shell', 'am', 'get-current-user']:
            out = self.user + '\n'
        elif 'AccountRemover' in args:
            out = 'FAIL:private-naver\n'
        elif 'android.settings.SYNC_SETTINGS' in args:
            self.screen = 0
            out = 'Status: ok\n'
        elif args[:3] == ['shell', 'uiautomator', 'dump'] and self.timeout:
            code = 124
        elif args[:2] == ['exec-out', 'cat']:
            out = [page(node('android:id/summary', 'NAVER', package='other.app' if self.foreign else cleanup.SETTINGS)),
                   page(node(cleanup.SETTINGS + ':id/entity_header_summary', 'NAVER'),
                        node(cleanup.SETTINGS + ':id/button', 'アカウントを削除')),
                   page(node(cleanup.SETTINGS + ':id/alertTitle', 'Unknown operation' if self.wrong_dialog else 'アカウントを削除しますか？'),
                        node('android:id/button1', '削除'))][self.screen]
        elif args[:3] == ['shell', 'input', 'tap']:
            self.screen += 1
            if self.screen == 3 and not self.refuse:
                self.naver -= 1
        return subprocess.CompletedProcess(command, code, out, '')


class AccountCleanupTest(unittest.TestCase):
    def setUp(self):
        self.device = Device()
        mock = patch.object(V6, 'run_command', side_effect=self.device.run)
        mock.start(); self.addCleanup(mock.stop)
        sleep = patch.object(cleanup.time, 'sleep')
        sleep.start(); self.addCleanup(sleep.stop)

    def test_removes_naver_and_preserves_samsung_without_uninstall(self):
        with self.assertLogs(level='INFO') as logs:
            V6.remove_non_samsung_accounts('TEST')
        self.assertEqual(self.device.naver, 0)
        self.assertFalse(any('private-' in line for line in logs.output))
        self.assertFalse(any('uninstall' in c or 'clear' in c for c in self.device.calls))
        self.assertTrue(any(c[3:6] == ['shell', 'rm', '-f'] for c in self.device.calls))

    def test_multiple_naver_accounts_are_all_removed(self):
        self.device.naver = 2
        V6.remove_non_samsung_accounts('TEST')
        self.assertEqual(self.device.naver, 0)

    def test_no_naver_never_opens_removal_ui(self):
        self.device.naver = 0
        V6.remove_non_samsung_accounts('TEST')
        self.assertFalse(any('uiautomator' in c for c in self.device.calls))

    def test_failed_removal_is_an_error_even_when_taps_succeed(self):
        self.device.refuse = True
        self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        self.assertEqual(self.device.naver, 1)

    def test_other_provider_and_unknown_dialog_cannot_be_confirmed(self):
        self.device.foreign = True
        self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        self.assertEqual(self.device.screen, 0)
        self.device.foreign = False; self.device.wrong_dialog = True
        self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        self.assertEqual(self.device.screen, 2)

    def test_other_accounts_remaining_are_still_failures(self):
        self.device.google = True
        self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        self.assertEqual(self.device.naver, 0)

    def test_query_failure_or_partial_inventory_never_means_zero(self):
        for dump in ['', 'Permission denied', 'Accounts: 1\n', 'Accounts: 0\nAccount {malformed}\n']:
            with self.subTest(dump=dump):
                self.device.broken_inventory = dump
                self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        self.assertFalse(any('AccountRemover' in c for c in self.device.calls))

    def test_timeout_and_missing_dex_are_errors(self):
        self.device.timeout = True
        self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        with patch.object(V6, 'push_dex_if_needed', return_value=False):
            self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')

    def test_non_owner_never_deletes_accounts(self):
        self.device.user = '10'
        self.assertRaises(RuntimeError, V6.remove_non_samsung_accounts, 'TEST')
        self.assertFalse(any('AccountRemover' in c or 'uiautomator' in c for c in self.device.calls))

    def test_process_never_reports_done_when_final_account_check_fails(self):
        progress = Mock()
        with patch.object(V6, 'PROGRESS', progress), patch.object(V6, 'get_device_model', return_value='SM-S948N'), \
                patch.object(V6, 'build_pipeline', return_value=[('noop', lambda: None)]):
            self.assertRaisesRegex(RuntimeError, '최종 계정 확인', V6.process_device, 'TEST', 'ja-JP')
        progress.fail.assert_called_once()
        progress.done.assert_not_called()

    def test_inventory_ignores_deleted_history_and_other_profiles(self):
        dump = ('User UserInfo{0:Owner:13}:\nAccounts: 0\n'
                'description=Account {name=private, type=com.nhn.android.naveraccount} is deleted\n'
                'User UserInfo{10:Work:13}:\nAccounts: 1\nAccount {name=private, type=com.google}\n')
        self.assertEqual([], cleanup.parse_accounts(dump))

    def test_no_retry_after_ui_tap_and_temporary_files_always_cleaned(self):
        seen = []
        def runner(cmd, **kwargs):
            seen.append((cmd, kwargs))
            return self.device.run(cmd, **kwargs)
        cleanup.remove('TEST', runner)
        self.assertTrue(all(k['retries'] == 0 for c, k in seen if c[3:6] == ['shell', 'input', 'tap']))
        self.assertTrue(any(k['retries'] == 1 for c, k in seen if c[-2:] == ['dumpsys', 'account']))
        self.assertTrue(any(c[3:6] == ['shell', 'rm', '-f'] for c, _ in seen))


if __name__ == '__main__':
    unittest.main()
