package com.forholiday.galaxyreset;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.io.IOException;

public class ResetPipelineTest {
    static class Fake implements ResetPipeline.Bridge {
        final List<String> commands = new ArrayList<>();
        String packages = "";
        boolean disconnectOnDisable;
        boolean failEnable;
        @Override public ResetPipeline.Result run(String command, int seconds) throws Exception {
            commands.add(command);
            if (command.startsWith("pm disable") && disconnectOnDisable) throw new ResetPipeline.ConnectionLost("offline");
            if (command.startsWith("pm enable") && failEnable) throw new ResetPipeline.ConnectionLost("offline");
            if (command.equals("pm list packages --user 0 -3")) return new ResetPipeline.Result(0, packages);
            return new ResetPipeline.Result(0, "Success\nSUCCESS");
        }
        @Override public void copy(String asset, String destination) {}
    }
    @Test public void protectsResetAndShizukuDuringUninstall() throws Exception {
        Fake fake = new Fake();
        fake.packages = "package:" + ResetPipeline.SELF + "\npackage:" + ResetPipeline.SHIZUKU + "\npackage:com.nhn.android.nmap\npackage:com.sec.android.app.sbrowser\npackage:com.customer.app\n";
        new ResetPipeline(fake, p -> {}, Set.of()).step(4, "ja-JP", "SM-S948N");
        assertEquals(List.of("pm list packages --user 0 -3", "pm uninstall --user 0 'com.customer.app'"), fake.commands);
    }
    @Test public void parsesWidgetsOnlyAndProtectsInfrastructure() {
        String dump = "Providers:\n provider=ProviderId{cmp:ComponentInfo{com.outside/.Widget}}\nWidgets:\n"
            + " provider=ProviderId{cmp:ComponentInfo{com.widget/.Widget}}\n"
            + " provider=ProviderId{cmp:ComponentInfo{" + ResetPipeline.SHIZUKU + "/.Widget}}\n"
            + "Hosts:\n provider=ProviderId{cmp:ComponentInfo{com.host/.Widget}}\nGrants:\n";
        assertEquals(Set.of("com.widget"), ResetPipeline.widgetProviders(dump));
    }
    @Test public void journalsBeforeDisableAndRetainsFailedRestoration() throws Exception {
        Fake fake = new Fake(); fake.disconnectOnDisable = true; fake.failEnable = true;
        List<Set<String>> writes = new ArrayList<>();
        ResetPipeline pipeline = new ResetPipeline(fake, p -> writes.add(new HashSet<>(p)), Set.of());
        try { pipeline.disableTemporarily(List.of("com.widget")); fail(); } catch (ResetPipeline.ConnectionLost expected) {}
        assertEquals(Set.of("com.widget"), writes.get(0));
        try { pipeline.restorePending(); fail(); } catch (ResetPipeline.ConnectionLost expected) {}
        assertEquals(Set.of("com.widget"), pipeline.pending);
        fake.failEnable = false; pipeline.restorePending(); assertTrue(pipeline.pending.isEmpty());
        assertEquals(Set.of(), writes.get(writes.size() - 1));
    }
    @Test public void failedInventoryCannotTriggerUninstallation() throws Exception {
        Fake fake = new Fake() {
            @Override public ResetPipeline.Result run(String cmd, int seconds) { return new ResetPipeline.Result(0, "package:com.customer.app", true); }
        };
        try { new ResetPipeline(fake, p -> {}, Set.of()).step(4, "ja-JP", "SM-S928N"); fail(); } catch (IOException expected) {}
        assertTrue(fake.commands.isEmpty());
    }
    @Test public void modelLanguageAndAccountRulesMatchV6() {
        assertEquals("S23", ResetPipeline.series("SM-S918N")); assertEquals("S24", ResetPipeline.series("SM-S928N"));
        assertEquals("S25", ResetPipeline.series("SM-S938N")); assertEquals("S26", ResetPipeline.series("SM-S948N"));
        assertEquals("S24", ResetPipeline.series("unknown"));
        assertEquals(List.of("ja-JP", "ko-KR", "en-US"), ResetPipeline.locales("ja-JP"));
        assertEquals(List.of("zh-CN", "ko-KR", "en-US", "ja-JP"), ResetPipeline.locales("zh-CN"));
        assertEquals(1, ResetPipeline.nonSamsungAccounts("Accounts: 3\nAccount {name=private, type=com.google}\nAccount {name=s, type=com.osp.app.signin}\nAccount {name=s, type=com.samsung.android.mobileservice}"));
        assertEquals(19, ResetPipeline.LABELS.length);
    }
    @Test public void uncertainEsimRequiresManualHandling() throws Exception {
        Fake fake = new Fake() {
            @Override public ResetPipeline.Result run(String cmd, int seconds) {
                commands.add(cmd); return new ResetPipeline.Result(0, "SUCCESS:ALL_ESIM_DISABLED\nFAIL:ALL_ESIM_DELETE_FAILED");
            }
        };
        ResetPipeline pipeline = new ResetPipeline(fake, p -> {}, Set.of()); pipeline.step(2, "ja-JP", "SM-S928N");
        assertTrue(pipeline.manual); assertFalse(pipeline.warnings.isEmpty());
        assertTrue(fake.commands.stream().anyMatch(s -> s.contains("MANAGE_EMBEDDED_SUBSCRIPTIONS")));
    }
    @Test public void quotesShellArgumentsWithoutInterpolation() {
        assertEquals("'a'\\''b'", ResetPipeline.quote("a'b"));
    }
    @Test public void storageVerificationIgnoresOnlySystemMarkers() {
        String listing = "/storage/emulated/0/Android/data/.nomedia\n/storage/emulated/0/Music/.thumbnails/.database_uuid\n";
        assertEquals(0, ResetPipeline.remainingUserFiles(listing));
        assertEquals(1, ResetPipeline.remainingUserFiles(listing + "/storage/emulated/0/Pictures/.thumbnails/private.jpg\n"));
        assertEquals(1, ResetPipeline.remainingUserFiles(listing + "/storage/emulated/0/Download/document.pdf\n"));
    }
    @Test public void widgetQueryReturnsOnlyActiveWidgetSection() throws Exception {
        Fake fake = new Fake() {
            @Override public ResetPipeline.Result run(String command, int seconds) {
                assertEquals(ResetPipeline.WIDGET_QUERY, command);
                return new ResetPipeline.Result(0, "Widgets:\n provider=ProviderId{cmp:ComponentInfo{com.widget/.Widget}}\nHosts:\n");
            }
        };
        assertEquals(Set.of("com.widget"), new ResetPipeline(fake, p -> {}, Set.of()).queryWidgetProviders());
    }
    @Test public void androidCompatibleAccountParserHandlesIndentedEntries() {
        assertEquals(1, ResetPipeline.nonSamsungAccounts("Accounts: 2\n  Account {name=contains}brace, type=com.google}\n  Account {name=s, type=com.samsung}\n"));
        assertThrows(IllegalArgumentException.class,()->ResetPipeline.nonSamsungAccounts("Account {malformed}\nSome unrelated output\n"));
    }
    @Test public void missingOptionalBrowserDoesNotFailOrInstall() throws Exception {
        Fake fake = new Fake() {
            @Override public ResetPipeline.Result run(String command, int seconds) {
                commands.add(command);
                if (command.startsWith("pm list packages")) return new ResetPipeline.Result(0, "");
                return new ResetPipeline.Result(0, "Success");
            }
        };
        ResetPipeline pipeline = new ResetPipeline(fake, p -> {}, Set.of());
        pipeline.step(13, "ja-JP", "SM-S948N");
        assertTrue(pipeline.warnings.isEmpty()); assertFalse(pipeline.manual);
        assertEquals(1, pipeline.notes.size());
        assertFalse(fake.commands.stream().anyMatch(c -> c.startsWith("cmd package install-existing") && c.contains("sbrowser")));
    }

    static class NmapFake extends Fake {
        long version=BuildConfig.NMAP_VERSION_CODE-1;
        boolean failWrite;
        final List<String> copies=new ArrayList<>();
        @Override public void copy(String asset,String destination){copies.add(asset);}
        @Override public ResetPipeline.Result run(String command,int seconds){
            commands.add(command);
            if(command.startsWith("pm list packages"))return new ResetPipeline.Result(0,"package:com.nhn.android.nmap\n");
            if(command.startsWith("dumpsys package"))return new ResetPipeline.Result(0,"versionCode="+version+" minSdk=26");
            if(command.startsWith("pm install-create"))return new ResetPipeline.Result(0,"Success: created install session [123]");
            if(command.startsWith("pm install-write")&&failWrite)return new ResetPipeline.Result(1,"Failure [INVALID_APK]");
            if(command.startsWith("pm install-commit"))version=BuildConfig.NMAP_VERSION_CODE;
            if(command.startsWith("cmd package resolve-activity"))return new ResetPipeline.Result(0,"com.nhn.android.nmap/com.naver.map.LaunchActivity\n");
            if(command.startsWith("am start"))return new ResetPipeline.Result(0,"Status: ok");
            if(command.startsWith("pidof"))return new ResetPipeline.Result(0,"1234");
            return new ResetPipeline.Result(0,"Success");
        }
    }
    ResetPipeline nmapPipeline(NmapFake fake){
        return new ResetPipeline(fake,p->{},Set.of());
    }
    @Test public void updatesNmapWithEverySplitAndVerifiesVersionAndLaunch() throws Exception {
        NmapFake fake=new NmapFake();nmapPipeline(fake).prepareNmap();
        assertEquals(Arrays.asList(BuildConfig.NMAP_APKS),fake.copies);
        assertEquals(BuildConfig.NMAP_APKS.length,fake.commands.stream().filter(c->c.startsWith("pm install-write")).count());
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pm install-commit")));
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pidof")));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm uninstall")));
    }
    @Test public void newerNmapSkipsInstallButChecksLaunch() throws Exception {
        NmapFake fake=new NmapFake();fake.version=BuildConfig.NMAP_VERSION_CODE+1;nmapPipeline(fake).prepareNmap();
        assertTrue(fake.copies.isEmpty());
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm install-create")));
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pidof")));
    }
    @Test public void failedNmapSplitAbandonsSessionAndNeverCommits() throws Exception {
        NmapFake fake=new NmapFake();fake.failWrite=true;
        try{nmapPipeline(fake).prepareNmap();fail("Incomplete splits cannot be installed");}catch(IOException expected){}
        assertTrue(fake.commands.contains("pm install-abandon 123"));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm install-commit")));
        assertEquals(BuildConfig.NMAP_APKS.length,fake.commands.stream().filter(c->c.startsWith("rm -f")).count());
    }
}
