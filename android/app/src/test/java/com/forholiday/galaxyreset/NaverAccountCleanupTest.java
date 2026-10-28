package com.forholiday.galaxyreset;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import org.w3c.dom.*;

public class NaverAccountCleanupTest {
    static String node(String pkg, String id, String text) {
        return "<node package='" + pkg + "' resource-id='" + id + "' text='" + text
            + "' enabled='true' bounds='[10,20][110,120]'/>";
    }
    static String settings(String id, String text) { return node("com.android.settings", id, text); }
    static String page(String nodes) { return "<?xml version='1.0'?><hierarchy>" + nodes + "</hierarchy>"; }
    static String dashboard(String provider) { return page(settings("android:id/summary", provider)); }
    static String detail() { return page(settings("com.android.settings:id/entity_header_summary", "NAVER")
        + settings("com.android.settings:id/button", "アカウントを削除")); }
    static String confirm() { return page(settings("com.android.settings:id/alertTitle", "アカウントを削除しますか？")
        + settings("android:id/button1", "削除")); }

    static class Fake extends ResetPipelineTest.Fake {
        int naver = 1, screen, taps;
        boolean google, refuse, wrongProvider, wrongDialog, timedOut;
        boolean session = true, clearFailure;
        @Override public ResetPipeline.Result run(String cmd, int seconds) {
            commands.add(cmd);
            if (cmd.equals("dumpsys account")) return new ResetPipeline.Result(0,
                "Accounts: " + (1 + naver + (google ? 1 : 0)) + "\nAccount {name=redacted, type=com.osp.app.signin}\n"
                + "Account {name=redacted, type=com.nhn.android.naveraccount}\n".repeat(naver)
                + (google ? "Account {name=redacted, type=com.google}\n" : ""));
            if (cmd.contains("AccountPreparer")) return new ResetPipeline.Result(0,"SUCCESS:AUTHENTICATORS_READY");
            if (cmd.startsWith("CLASSPATH=")) return new ResetPipeline.Result(0, "FAIL:BACKGROUND_REMOVAL");
            if (cmd.startsWith("pm list packages")) return new ResetPipeline.Result(0, "package:com.nhn.android.nmap\n");
            if (cmd.equals("pm clear --user 0 com.nhn.android.nmap")) {
                if (clearFailure) return new ResetPipeline.Result(0, "Failed");
                session = false; return new ResetPipeline.Result(0, "Success\n");
            }
            if (cmd.contains("android.settings.SYNC_SETTINGS")) { screen = 0; return new ResetPipeline.Result(0, "Status: ok"); }
            if (cmd.startsWith("uiautomator")) {
                if (timedOut) return new ResetPipeline.Result(124, "");
                return new ResetPipeline.Result(0, switch (screen) {
                    case 0 -> dashboard(wrongProvider ? "Samsungアカウント" : "NAVER");
                    case 1 -> detail();
                    default -> wrongDialog ? page(settings("android:id/button1", "削除")) : confirm();
                });
            }
            if (cmd.startsWith("input tap")) {
                taps++; screen++;
                if (screen == 3 && !refuse) naver--;
            }
            return new ResetPipeline.Result(0, "Success");
        }
    }
    static ResetPipeline pipeline(Fake fake) {
        return new ResetPipeline(fake, p -> {}, Set.of());
    }
    @Test public void removesOnlyNaverViaSettingsAfterBackgroundRefusal() throws Exception {
        Fake fake = new Fake(); ResetPipeline pipeline = pipeline(fake);
        pipeline.step(1, "ja-JP", "SM-S948N");
        assertEquals(0, fake.naver); assertEquals(3, fake.taps);
        assertFalse(pipeline.manual); assertTrue(pipeline.warnings.isEmpty());
        assertTrue(pipeline.notes.stream().anyMatch(n -> n.contains("인증 정보")));
        assertFalse(fake.commands.stream().anyMatch(c -> c.contains("uninstall")));
        assertFalse(fake.session);
        assertTrue(fake.commands.stream().anyMatch(c -> c.startsWith("rm -f")));
    }
    @Test public void removesMultipleNaverAccountsAndChecksInventoryEachTime() throws Exception {
        Fake fake = new Fake(); fake.naver = 2;
        new NaverAccountCleanup(pipeline(fake)).remove();
        assertEquals(0, fake.naver); assertEquals(6, fake.taps);
    }
    @Test public void zeroNaverDoesNotOpenSettings() throws Exception {
        Fake fake = new Fake(); fake.naver = 0;
        new NaverAccountCleanup(pipeline(fake)).remove();
        assertEquals(List.of("dumpsys account"), fake.commands);
    }
    @Test public void zeroSystemAccountsStillClearsMapSession() throws Exception {
        Fake fake = new Fake(); fake.naver = 0;
        ResetPipeline pipeline = pipeline(fake); pipeline.step(1, "ja-JP", "SM-S948N");
        assertFalse(fake.session); assertTrue(pipeline.warnings.isEmpty());
        assertFalse(fake.commands.stream().anyMatch(c -> c.contains("SYNC_SETTINGS")));
    }
    @Test public void failedSessionClearCannotBeReportedAsAccountSuccess() throws Exception {
        Fake fake = new Fake(); fake.naver = 0; fake.clearFailure = true;
        assertThrows(Exception.class, () -> pipeline(fake).step(1, "ja-JP", "SM-S948N"));
        assertTrue(fake.session);
    }
    static class MeetFake extends Fake {
        static final String MEET = "com.google.android.apps.tachyon";
        int meet = 1;
        boolean enabled, meetSession = true, failEnable, failClear;
        MeetFake() { naver = 0; }
        @Override public ResetPipeline.Result run(String cmd, int seconds) {
            if (cmd.equals("dumpsys account")) {
                commands.add(cmd);
                return new ResetPipeline.Result(0, "Accounts: " + (1 + meet)
                    + "\nAccount {name=redacted, type=com.osp.app.signin}\n"
                    + "Account {name=Meet, type=com.google.android.apps.tachyon}\n".repeat(meet));
            }
            if (cmd.contains("AccountPreparer")) {
                commands.add(cmd);enabled=!failEnable;
                return new ResetPipeline.Result(enabled?0:1,enabled?"SUCCESS:AUTHENTICATORS_READY":"FAIL:PREPARATION");
            }
            if (cmd.startsWith("pm list packages") && cmd.contains(MEET)) {
                commands.add(cmd);
                return new ResetPipeline.Result(0, !cmd.contains(" -e ") || enabled ? "package:" + MEET + "\n" : "");
            }
            if (cmd.startsWith("CLASSPATH=") && enabled) meet = 0;
            if (cmd.equals("pm clear --user 0 " + MEET)) {
                commands.add(cmd); if (!failClear) meetSession = false;
                return new ResetPipeline.Result(0, failClear ? "Failed" : "Success");
            }
            return super.run(cmd, seconds);
        }
    }
    @Test public void disabledMeetIsEnabledRemovedAndCleared() throws Exception {
        MeetFake fake = new MeetFake(); ResetPipeline pipeline = pipeline(fake);
        pipeline.step(1, "en-US", "SM-S938N");
        assertTrue(fake.enabled); assertEquals(0, fake.meet); assertFalse(fake.meetSession);
        assertFalse(pipeline.manual); assertTrue(pipeline.warnings.isEmpty());
        int enable = 0;while(!fake.commands.get(enable).contains("AccountPreparer"))enable++;
        int dex = 0; while (!fake.commands.get(dex).contains("AccountRemover")) dex++;
        assertTrue(enable < dex);
    }
    @Test public void meetEnableAndSessionFailuresRemainFailures() {
        MeetFake disabled = new MeetFake(); disabled.failEnable = true;
        assertThrows(Exception.class, () -> pipeline(disabled).step(1, "en-US", "SM-S938N"));
        MeetFake clear = new MeetFake(); clear.failClear = true;
        assertThrows(Exception.class, () -> pipeline(clear).step(1, "en-US", "SM-S938N"));
        assertTrue(clear.meetSession);
    }
    @Test public void refusesToSelectSamsungOrAnotherProvider() throws Exception {
        Fake fake = new Fake(); fake.wrongProvider = true;
        ResetPipeline pipeline = pipeline(fake); pipeline.step(1, "ja-JP", "SM-S948N");
        assertEquals(0, fake.taps); assertEquals(1, fake.naver);
        assertTrue(pipeline.manual); assertFalse(pipeline.warnings.isEmpty());
    }
    @Test public void neverConfirmsAnUnrecognizedDialog() throws Exception {
        Fake fake = new Fake(); fake.wrongDialog = true;
        ResetPipeline pipeline = pipeline(fake); pipeline.step(1, "ja-JP", "SM-S948N");
        assertEquals(2, fake.taps); assertEquals(1, fake.naver); assertTrue(pipeline.manual);
    }
    @Test public void successfulTapsDoNotProveRemoval() throws Exception {
        Fake fake = new Fake(); fake.refuse = true;
        ResetPipeline pipeline = pipeline(fake); pipeline.step(1, "ja-JP", "SM-S948N");
        assertEquals(1, fake.naver); assertTrue(pipeline.manual); assertFalse(pipeline.warnings.isEmpty());
    }
    @Test public void otherAccountFailuresRemainFailures() throws Exception {
        Fake fake = new Fake(); fake.google = true;
        ResetPipeline pipeline = pipeline(fake); pipeline.step(1, "ja-JP", "SM-S948N");
        assertEquals(0, fake.naver); assertTrue(pipeline.manual); assertFalse(pipeline.warnings.isEmpty());
    }
    @Test public void screenTimeoutIsNotSuccess() throws Exception {
        Fake fake = new Fake(); fake.timedOut = true;
        ResetPipeline pipeline = pipeline(fake); pipeline.step(1, "ja-JP", "SM-S948N");
        assertEquals(0, fake.taps); assertTrue(pipeline.manual); assertFalse(pipeline.warnings.isEmpty());
    }
    @Test public void historyAndAuthenticatorRegistrationAreNotCurrentAccounts() {
        String dump = "Accounts: 1\nAccount {name=redacted, type=com.osp.app.signin}\n"
            + "description=Account {name=redacted, type=com.nhn.android.naveraccount} is deleted\n"
            + "ServiceInfo: AuthenticatorDescription {type=com.nhn.android.naveraccount}\n";
        assertEquals(0, ResetPipeline.naverAccounts(dump));
        assertEquals(0, ResetPipeline.nonSamsungAccounts(dump));
    }
    @Test public void rejectsOtherAppButtonsAndUnsafeXml() throws Exception {
        Document foreign = NaverAccountCleanup.parse(page(node("other.app", "android:id/summary", "NAVER")));
        assertNull(NaverAccountCleanup.naverRow(foreign));
        Document noHeader = NaverAccountCleanup.parse(page(settings("com.android.settings:id/button", "계정 삭제")));
        assertNull(NaverAccountCleanup.removeButton(noHeader));
        assertThrows(Exception.class, () -> NaverAccountCleanup.parse("<!DOCTYPE h><hierarchy/>"));
        Element disabled = NaverAccountCleanup.naverRow(NaverAccountCleanup.parse(dashboard("NAVER").replace("enabled='true'", "enabled='false'")));
        assertThrows(Exception.class, () -> NaverAccountCleanup.center(disabled));
    }
}
