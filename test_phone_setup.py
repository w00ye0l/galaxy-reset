"""PC → phone provisioning regressions. Never invokes real ADB or deletes device data."""
import hashlib
import json
import shlex
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import V6


class FakeDevice:
    def __init__(self):
        self.installed = {'com.nhn.android.nmap', 'com.alphainventor.filemanager',
                          V6.PHONE_APP_PACKAGE, V6.SHIZUKU_PACKAGE}
        self.versions = {V6.PHONE_APP_PACKAGE: 7, V6.SHIZUKU_PACKAGE: 1086, V6.NMAP_PACKAGE: V6.load_nmap_bundle()['versionCode']}
        self.calls = []
        self.sdk = '37'
        self.user = '0'
        self.wireless = '1'
        self.running = True
        self.install_failure = False
        self.grant_failure = False
        self.nmap_alive = True

    def run(self, command, **kwargs):
        self.calls.append(list(command))
        a = command[3:]
        code, out, err = 0, '', ''
        if a[:4] == ['shell', 'pm', 'list', 'packages']:
            out = ''.join('package:%s\n' % p for p in sorted(self.installed))
        elif a[:3] == ['shell', 'dumpsys', 'package']:
            out = ('codePath=/data/app/~~ABC=/moe.shizuku.privileged.api-XYZ==\n'
                   'primaryCpuAbi=arm64-v8a\nversionCode=%d minSdk=24\n' % self.versions[a[-1]])
        elif a[:1] in (['install'], ['install-multiple']):
            if self.install_failure:
                code, out = 1, 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'
            else:
                pkg = V6.NMAP_PACKAGE if a[0] == 'install-multiple' else {'galaxy-reset.apk': V6.PHONE_APP_PACKAGE, 'shizuku.apk': V6.SHIZUKU_PACKAGE,
                       'nmap.apk': 'com.nhn.android.nmap', 'filemanager.apk': 'com.alphainventor.filemanager'}[Path(a[-1]).name]
                self.installed.add(pkg)
                if pkg == V6.NMAP_PACKAGE: self.versions[pkg] = V6.load_nmap_bundle()['versionCode']
                elif pkg in (V6.PHONE_APP_PACKAGE, V6.SHIZUKU_PACKAGE): self.versions[pkg] = V6.load_phone_bundle()[pkg]['versionCode']
                out = 'Success\n'
        elif a[:3] == ['shell', 'pm', 'path']:
            out = 'package:/data/app/%s/base.apk\n' % a[-1] if a[-1] in self.installed else ''
        elif a[:3] == ['shell', 'getprop', 'ro.build.version.sdk']:
            out = self.sdk + '\n'
        elif a[:3] == ['shell', 'am', 'get-current-user']:
            out = self.user + '\n'
        elif a[:4] == ['shell', 'settings', 'get', 'global']:
            out = self.wireless + '\n'
        elif a[:4] == ['shell', 'cmd', 'package', 'resolve-activity']:
            out = 'com.nhn.android.nmap/com.naver.map.LaunchActivity\n'
        elif a[:3] == ['shell', 'am', 'start'] and a[-1].startswith('com.nhn.android.nmap/'):
            out = 'Status: ok\n'
        elif a[:3] == ['shell', 'pidof', V6.NMAP_PACKAGE]:
            code, out = (0, '5555\n') if self.nmap_alive else (1, '')
        elif a[:3] == ['shell', 'pidof', 'shizuku_server']:
            code, out = (0, '2345\n') if self.running else (1, '')
        elif len(a) == 2 and 'libshizuku.so' in a[1]:
            self.running = True
        elif a[:3] == ['shell', 'pm', 'grant'] and self.grant_failure:
            code, err = 1, 'SecurityException: permission denied'
        return subprocess.CompletedProcess(command, code, out, err)


class PhoneSetupTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeDevice()
        self.bundle = V6.load_phone_bundle()
        self.fake.versions[V6.PHONE_APP_PACKAGE] = self.bundle[V6.PHONE_APP_PACKAGE]['versionCode']
        self.mock = patch.object(V6, 'run_command', side_effect=self.fake.run)
        self.mock.start()
        sleep = patch.object(V6.time, 'sleep'); sleep.start(); self.addCleanup(sleep.stop)
        self.addCleanup(self.mock.stop)
        V6.POST_RUN_NOTICES.clear()

    def test_installs_both_phone_apps_when_missing(self):
        self.fake.installed -= {V6.PHONE_APP_PACKAGE, V6.SHIZUKU_PACKAGE}
        V6.ensure_essential_apps_installed('TEST')
        files = [Path(c[-1]).name for c in self.fake.calls if c[3] == 'install']
        self.assertEqual(files, ['galaxy-reset.apk', 'shizuku.apk'])
        self.assertTrue({V6.PHONE_APP_PACKAGE, V6.SHIZUKU_PACKAGE} <= self.fake.installed)
        self.assertFalse(any('uninstall' in c for c in self.fake.calls))

    def test_updates_old_reset_app_without_uninstalling(self):
        self.fake.versions[V6.PHONE_APP_PACKAGE] -= 1
        V6.ensure_essential_apps_installed('TEST')
        installs = [c for c in self.fake.calls if c[3] == 'install']
        self.assertEqual(len(installs), 1)
        self.assertIn('-r', installs[0])
        self.assertEqual(Path(installs[0][-1]).name, 'galaxy-reset.apk')

    def test_never_downgrades_newer_phone_app(self):
        self.fake.versions[V6.PHONE_APP_PACKAGE] += 100
        V6.ensure_essential_apps_installed('TEST')
        self.assertFalse(any(c[3] == 'install' for c in self.fake.calls))

    def test_old_nmap_updates_complete_split_set(self):
        self.fake.versions[V6.NMAP_PACKAGE] = 5120400
        V6.ensure_essential_apps_installed('TEST')
        commands = [c for c in self.fake.calls if c[3] == 'install-multiple']
        self.assertEqual(len(commands), 1)
        self.assertEqual(commands[0][7:], V6.load_nmap_bundle()['apk_paths'])
        self.assertIn('-r', commands[0])

    def test_newer_nmap_is_preserved_and_still_launch_checked(self):
        self.fake.versions[V6.NMAP_PACKAGE] += 1
        V6.ensure_essential_apps_installed('TEST')
        self.assertFalse(any(c[3] == 'install-multiple' for c in self.fake.calls))
        self.assertTrue(any(c[-1] == V6.NMAP_PACKAGE and 'pidof' in c for c in self.fake.calls))

    def test_nmap_immediate_exit_fails_preparation(self):
        self.fake.nmap_alive = False
        with self.assertRaises(RuntimeError):
            V6.ensure_essential_apps_installed('TEST')
        self.assertFalse(V6.POST_RUN_NOTICES)
        self.assertTrue(any('force-stop' in c and c[-1] == V6.NMAP_PACKAGE for c in self.fake.calls))

    def test_signature_failure_stops_setup_and_never_uninstalls(self):
        self.fake.versions[V6.PHONE_APP_PACKAGE] -= 1
        self.fake.install_failure = True
        with self.assertRaisesRegex(RuntimeError, 'UPDATE_INCOMPATIBLE'):
            V6.ensure_essential_apps_installed('TEST')
        self.assertFalse(any('uninstall' in c for c in self.fake.calls))
        self.assertFalse(any('settings' in c for c in self.fake.calls))

    def test_user_app_cleanup_preserves_reset_shizuku_and_browser(self):
        self.fake.installed |= {'com.customer.app', 'com.sec.android.app.sbrowser'}
        V6.delete_user_installed_apps('TEST')
        removed = [c[-1] for c in self.fake.calls if 'uninstall' in c]
        self.assertEqual(removed, ['com.customer.app'])

    def test_setup_requires_owner_profile_before_changing_settings(self):
        self.fake.user = '10'
        with self.assertRaisesRegex(RuntimeError, '사용자 0'):
            V6.prepare_wireless_reset('TEST')
        self.assertFalse(any('grant' in c or 'settings' in c for c in self.fake.calls))

    def test_setup_requires_android_11(self):
        self.fake.sdk = '29'
        with self.assertRaisesRegex(RuntimeError, 'Android 11'):
            V6.prepare_wireless_reset('TEST')
        self.assertFalse(any('grant' in c for c in self.fake.calls))

    def test_rejected_permission_is_not_reported_as_success(self):
        self.fake.grant_failure = True
        with self.assertRaisesRegex(RuntimeError, 'permission denied'):
            V6.prepare_wireless_reset('TEST')
        self.assertFalse(V6.POST_RUN_NOTICES)

    def test_wireless_disabled_does_not_claim_ready(self):
        self.fake.wireless = '0'
        with self.assertRaisesRegex(RuntimeError, '무선 디버깅 활성화 확인 실패'):
            V6.prepare_wireless_reset('TEST')
        self.assertFalse(V6.POST_RUN_NOTICES)

    def test_starter_uses_official_installed_binary_and_verifies_pid(self):
        self.fake.running = False
        V6.start_shizuku_from_pc('TEST')
        starts = [c[-1] for c in self.fake.calls if len(c) == 5 and 'libshizuku.so' in c[-1]]
        self.assertEqual(len(starts), 1)
        arguments = shlex.split(starts[0])
        self.assertTrue(arguments[0].endswith('/lib/arm64/libshizuku.so'))
        self.assertEqual(arguments[1], '--apk=' + arguments[0].split('/lib/')[0] + '/base.apk')
        self.assertTrue(self.fake.running)

    def test_initial_pairing_and_permission_notice_remains_truthful(self):
        V6.prepare_wireless_reset('TEST')
        self.assertIn('USB 기동만으로 무선 페어링 완료를 뜻하지 않습니다', V6.POST_RUN_NOTICES[-1])
        self.assertFalse(any('pair' in c for c in self.fake.calls))
        for pkg in (V6.PHONE_APP_PACKAGE, V6.SHIZUKU_PACKAGE):
            self.assertTrue(any(pkg in c and 'android.permission.WRITE_SECURE_SETTINGS' in c for c in self.fake.calls))

    def test_bundle_tampering_is_detected_before_reset(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'phone-app'; root.mkdir()
            apps = []
            for pkg, name in V6.PHONE_APK_FILES.items():
                p = root / name
                with zipfile.ZipFile(p, 'w') as archive:
                    archive.writestr('AndroidManifest.xml', b'manifest')
                    archive.writestr('classes.dex', b'dex\n')
                apps.append({'package': pkg, 'file': name, 'versionCode': 1,
                             'sha256': hashlib.sha256(p.read_bytes()).hexdigest()})
            (root / 'bundle.json').write_text(json.dumps({'apps': apps}))
            with zipfile.ZipFile(root / 'galaxy-reset.apk', 'a') as archive:
                archive.writestr('changed', 'tampered')
            with patch.object(V6, 'resource_path', side_effect=lambda p: str(Path(temp) / p)):
                with self.assertRaisesRegex(RuntimeError, '해시 불일치'):
                    V6.load_phone_bundle(verify_hash=True)

    def test_missing_bundle_blocks_main_before_device_reset(self):
        with patch.object(V6, 'load_phone_bundle', side_effect=RuntimeError('missing APK')), patch.object(V6, 'wait_for_ready_devices') as wait:
            with self.assertRaisesRegex(RuntimeError, 'missing APK'):
                V6.main()
            wait.assert_not_called()

    def test_prepare_only_mode_never_enters_reset_pipeline(self):
        with patch('sys.argv', ['V6.py', '--prepare-phone-only', '--serial', 'TEST']), patch.object(V6, 'load_phone_bundle'), patch.object(V6, 'ensure_essential_apps_installed') as install, patch.object(V6, 'build_pipeline') as pipeline:
            V6.prepare_phone_only_main()
            install.assert_called_once_with('TEST')
            pipeline.assert_not_called()
        self.assertEqual(V6.PIPELINE_STEP_COUNT, 19)


if __name__ == '__main__':
    unittest.main()
