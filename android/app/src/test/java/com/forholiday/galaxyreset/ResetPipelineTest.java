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
        fake.packages = "package:" + ResetPipeline.SELF + "\npackage:" + ResetPipeline.SHIZUKU + "\npackage:com.nhn.android.nmap\npackage:com.sec.android.app.sbrowser\npackage:com.sec.knox.kccagent\npackage:com.customer.app\n";
        ResetPipeline pipeline=new ResetPipeline(fake, p -> {}, Set.of());pipeline.step(0, "ja-JP", "SM-S948N");
        assertEquals(List.of("pm list packages --user 0 -3", "pm uninstall --user 0 'com.customer.app'"), fake.commands);
        assertTrue(pipeline.warnings.isEmpty());
        assertTrue(pipeline.notes.contains("Knox Configure 관리 앱 보존 · 관리자 등록 유지"));
    }
    @Test public void knoxExceptionDoesNotHideOtherPackageDeletionFailures() throws Exception {
        Fake fake=new Fake(){
            @Override public ResetPipeline.Result run(String cmd,int seconds) throws Exception {
                if(cmd.startsWith("pm uninstall")){commands.add(cmd);return new ResetPipeline.Result(1,"Failure [DELETE_FAILED_INTERNAL_ERROR]");}
                return super.run(cmd,seconds);
            }
        };
        fake.packages="package:com.sec.knox.kccagent\npackage:com.sec.knox.kccagent.extra\n";
        ResetPipeline pipeline=new ResetPipeline(fake,p->{},Set.of());pipeline.step(0,"ja-JP","SM-S918N");
        assertTrue(fake.commands.contains("pm uninstall --user 0 'com.sec.knox.kccagent.extra'"));
        assertFalse(fake.commands.contains("pm uninstall --user 0 'com.sec.knox.kccagent'"));
        assertFalse(pipeline.warnings.isEmpty());
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
        try { new ResetPipeline(fake, p -> {}, Set.of()).step(0, "ja-JP", "SM-S928N"); fail(); } catch (IOException expected) {}
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
        ResetPipeline pipeline = new ResetPipeline(fake, p -> {}, Set.of()); pipeline.step(3, "ja-JP", "SM-S928N");
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
    static class StorageFake extends Fake {
        final Deque<ResetPipeline.Result> listings = new ArrayDeque<>();
        int deletionCode;
        boolean lost;
        @Override public ResetPipeline.Result run(String command, int seconds) throws Exception {
            commands.add(command);
            if (lost) throw new ResetPipeline.ConnectionLost("offline");
            return command.startsWith("find ") ? listings.removeFirst()
                : new ResetPipeline.Result(deletionCode, deletionCode == 0 ? "" : "Directory not empty");
        }
    }
    ResetPipeline storagePipeline(StorageFake fake) {
        return new ResetPipeline(fake, p -> {}, Set.of());
    }
    @Test public void storageRmWarningDoesNotOverrideVerifiedEmptyInventory() throws Exception {
        StorageFake fake = new StorageFake(); fake.deletionCode = 1;
        fake.listings.add(new ResetPipeline.Result(0, ""));
        ResetPipeline pipeline = storagePipeline(fake); pipeline.step(4, "ja-JP", "SM-S948N");
        assertTrue(pipeline.warnings.isEmpty()); assertEquals(3, fake.commands.size());
    }
    @Test public void storageRetriesRemainingFileAndVerifiesAgain() throws Exception {
        StorageFake fake = new StorageFake();
        fake.listings.add(new ResetPipeline.Result(0, "/storage/emulated/0/Download/customer.pdf\n"));
        fake.listings.add(new ResetPipeline.Result(0, ""));
        ResetPipeline pipeline = storagePipeline(fake); pipeline.step(4, "ja-JP", "SM-S948N");
        assertTrue(pipeline.warnings.isEmpty()); assertEquals(6, fake.commands.size());
        assertTrue(pipeline.notes.get(0).contains("자동 재시도"));
    }
    @Test public void storagePersistentFilesStillRequireAttention() throws Exception {
        StorageFake fake = new StorageFake();
        for (int i = 0; i < 2; i++) fake.listings.add(new ResetPipeline.Result(0, "/storage/emulated/0/Download/customer.pdf\n"));
        ResetPipeline pipeline = storagePipeline(fake); pipeline.step(4, "ja-JP", "SM-S948N");
        assertTrue(pipeline.manual); assertFalse(pipeline.warnings.isEmpty());
        assertEquals(6, fake.commands.size());
    }
    @Test public void storageFailedOrTruncatedInventoryCannotPass() {
        for (ResetPipeline.Result result : List.of(new ResetPipeline.Result(1, "Permission denied"), new ResetPipeline.Result(0, "", true))) {
            StorageFake fake = new StorageFake(); fake.listings.add(result);
            assertThrows(IOException.class, () -> storagePipeline(fake).step(4, "ja-JP", "SM-S948N"));
        }
    }
    @Test public void storageTimeoutAndDisconnectCannotPass() {
        StorageFake timeout = new StorageFake(); timeout.deletionCode = 124;
        assertThrows(IOException.class, () -> storagePipeline(timeout).step(4, "ja-JP", "SM-S948N"));
        StorageFake lost = new StorageFake(); lost.lost = true;
        assertThrows(ResetPipeline.ConnectionLost.class, () -> storagePipeline(lost).step(4, "ja-JP", "SM-S948N"));
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
        pipeline.step(14, "ja-JP", "SM-S948N");
        assertTrue(pipeline.warnings.isEmpty()); assertFalse(pipeline.manual);
        assertEquals(1, pipeline.notes.size());
        assertFalse(fake.commands.stream().anyMatch(c -> c.startsWith("cmd package install-existing") && c.contains("sbrowser")));
    }

    static class NmapFake extends Fake {
        long version=BuildConfig.NMAP_VERSION_CODE-1;
        boolean failWrite, disabled, ignoreEnable;
        final List<String> copies=new ArrayList<>();
        @Override public void copy(String asset,String destination){copies.add(asset);}
        @Override public ResetPipeline.Result run(String command,int seconds){
            commands.add(command);
            if(command.startsWith("pm list packages"))return new ResetPipeline.Result(0,command.contains(" -e ")&&disabled?"":"package:com.nhn.android.nmap\n");
            if(command.startsWith("dumpsys package"))return new ResetPipeline.Result(0,"versionCode="+version+" minSdk=26");
            if(command.startsWith("pm install-create"))return new ResetPipeline.Result(0,"Success: created install session [123]");
            if(command.startsWith("pm install-write")&&failWrite)return new ResetPipeline.Result(1,"Failure [INVALID_APK]");
            if(command.startsWith("pm install-commit"))version=BuildConfig.NMAP_VERSION_CODE;
            if(command.startsWith("pm enable")&&!ignoreEnable)disabled=false;
            return new ResetPipeline.Result(0,"Success");
        }
    }
    ResetPipeline nmapPipeline(NmapFake fake){
        return new ResetPipeline(fake,p->{},Set.of());
    }
    @Test public void updatesNmapWithEverySplitAndChecksEnabledWithoutOpeningApp() throws Exception {
        NmapFake fake=new NmapFake();fake.disabled=true;nmapPipeline(fake).prepareNmap();
        assertEquals(Arrays.asList(BuildConfig.NMAP_APKS),fake.copies);
        assertEquals(BuildConfig.NMAP_APKS.length,fake.commands.stream().filter(c->c.startsWith("pm install-write")).count());
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pm install-commit")));
        assertFalse(fake.disabled);
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pm list packages -e")));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("am ")||c.startsWith("pidof")||c.contains("resolve-activity")));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm uninstall")));
    }
    @Test public void newerNmapSkipsInstallButChecksEnabledWithoutOpeningApp() throws Exception {
        NmapFake fake=new NmapFake();fake.version=BuildConfig.NMAP_VERSION_CODE+1;nmapPipeline(fake).prepareNmap();
        assertTrue(fake.copies.isEmpty());
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm install-create")));
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pm list packages -e")));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("am ")||c.startsWith("pidof")||c.contains("resolve-activity")));
    }
    @Test public void nmapStillDisabledAfterEnableCannotPass() throws Exception {
        NmapFake fake=new NmapFake();fake.disabled=true;fake.ignoreEnable=true;
        assertThrows(IOException.class,()->nmapPipeline(fake).prepareNmap());
    }
    @Test public void failedNmapSplitAbandonsSessionAndNeverCommits() throws Exception {
        NmapFake fake=new NmapFake();fake.failWrite=true;
        try{nmapPipeline(fake).prepareNmap();fail("Incomplete splits cannot be installed");}catch(IOException expected){}
        assertTrue(fake.commands.contains("pm install-abandon 123"));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm install-commit")));
        assertEquals(BuildConfig.NMAP_APKS.length,fake.commands.stream().filter(c->c.startsWith("rm -f")).count());
    }
    static class EssentialFake extends NmapFake {
        final Set<String> disabledPackages=new HashSet<>();
        EssentialFake(){version=BuildConfig.NMAP_VERSION_CODE;}
        @Override public ResetPipeline.Result run(String command,int seconds) {
            if(command.startsWith("pm list packages")) {
                commands.add(command);
                String pkg=command.substring(command.lastIndexOf(' ')+1).replace("'", "");
                return new ResetPipeline.Result(0,command.contains(" -e ")&&disabledPackages.contains(pkg)?"":"package:"+pkg+"\n");
            }
            if(command.startsWith("pm enable")) {
                String pkg=command.substring(command.lastIndexOf(' ')+1).replace("'", "");
                if(pkg.equals(ResetPipeline.SELF)||pkg.equals(ResetPipeline.SHIZUKU))
                    throw new AssertionError("Package state change kills live reset infrastructure");
                disabledPackages.remove(pkg);
            }
            return super.run(command,seconds);
        }
    }
    @Test public void essentialStepNeverChangesActiveSelfOrShizukuPackageState() throws Exception {
        EssentialFake fake=new EssentialFake();
        fake.disabledPackages.add("com.alphainventor.filemanager");
        ResetPipeline pipeline=nmapPipeline(fake);pipeline.step(13,"ja-JP","SM-S938N");
        assertTrue(pipeline.warnings.isEmpty());
        assertTrue(fake.disabledPackages.isEmpty());
        assertEquals(1,fake.commands.stream().filter(c->c.startsWith("pm enable")).count());
        assertTrue(fake.commands.stream().anyMatch(c->c.startsWith("pm enable")&&c.contains("com.alphainventor.filemanager")));
    }
    @Test public void disabledInfrastructureIsReportedWithoutTryingToRestartIt() throws Exception {
        for(String pkg:List.of(ResetPipeline.SELF,ResetPipeline.SHIZUKU)) {
            EssentialFake fake=new EssentialFake();fake.disabledPackages.add(pkg);
            ResetPipeline failed=nmapPipeline(fake); failed.step(13,"ja-JP","SM-S938N"); assertFalse(failed.warnings.isEmpty());
            assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm enable")));
        }
    }
    @Test public void fileManagerDeepSleepIsAdvisoryAndNeverUninstalls() throws Exception {
        EssentialFake fake = new EssentialFake() {
            @Override public ResetPipeline.Result run(String cmd, int sec) {
                if (cmd.startsWith("pm enable") && cmd.contains("com.alphainventor.filemanager"))
                    return new ResetPipeline.Result(255, "SecurityException: Shell cannot change component state");
                return super.run(cmd, sec);
            }
        };
        fake.disabledPackages.add("com.alphainventor.filemanager");
        ResetPipeline pipeline=nmapPipeline(fake); pipeline.step(13,"ko-KR","SM-S938N");
        assertTrue(pipeline.warnings.isEmpty());
        assertTrue(pipeline.advisories.stream().anyMatch(n->n.contains("딥 슬립")));
        assertFalse(fake.commands.stream().anyMatch(c->c.startsWith("pm uninstall")));
    }
    @Test public void dataClearContinuesAfterOneFailureAndCachesOnlySuccesses() throws Exception {
        Fake fake = new Fake() {
            @Override public ResetPipeline.Result run(String cmd, int seconds) {
                commands.add(cmd);
                if(cmd.startsWith("pm list packages"))return new ResetPipeline.Result(0,"package:"+cmd.substring(cmd.lastIndexOf(' ')+1).replace("'", ""));
                return new ResetPipeline.Result(0,cmd.contains("bad.app")?"Failed":"Success");
            }
        };
        ResetPipeline pipeline=new ResetPipeline(fake,p->{},Set.of());
        pipeline.clear("bad.app","good.app"); pipeline.clear("good.app");
        assertFalse(pipeline.warnings.isEmpty());
        assertEquals(1,fake.commands.stream().filter(c->c.startsWith("pm clear")&&c.contains("good.app")).count());
        assertFalse(pipeline.cleared.contains("bad.app"));
    }

    static class FinalFake extends Fake {
        boolean remainingAccount, remainingApp, brokenAccounts;
        @Override public ResetPipeline.Result run(String cmd,int seconds) {
            commands.add(cmd);
            if(cmd.equals("dumpsys account"))return new ResetPipeline.Result(0,brokenAccounts?"Permission denied":remainingAccount?"Accounts: 1\nAccount {name=redacted, type=com.google}\n":"Accounts: 0\n");
            if(cmd.equals("pm list packages --user 0 -3"))return new ResetPipeline.Result(0,remainingApp?"package:customer.app\n":"");
            if(cmd.startsWith("pm list packages"))return new ResetPipeline.Result(0,"package:"+cmd.substring(cmd.lastIndexOf(' ')+1).replace("'", "")+"\n");
            if(cmd.startsWith("dumpsys package"))return new ResetPipeline.Result(0,"versionCode=999999999 minSdk=30");
            if(cmd.startsWith("dumpsys wallpaper"))return new ResetPipeline.Result(0,"mName=WallpaperSetter");
            return new ResetPipeline.Result(0,"ko-KR");
        }
    }
    @Test public void finalVerificationCannotHideRemainingCustomerAccountsOrApps() throws Exception {
        FinalFake fake=new FinalFake();fake.remainingAccount=true;fake.remainingApp=true;
        ResetPipeline p=new ResetPipeline(fake,x->{},Set.of());p.step(17,"ko-KR","SM-S938N");
        assertEquals(1,p.finalAccountCount);assertFalse(p.finalUserAppsClear);assertFalse(p.warnings.isEmpty());
        assertFalse(p.summaryText.contains("남은 비삼성 계정: 0개"));
    }
    @Test public void unknownAccountInventoryIsNotZero() throws Exception {
        FinalFake fake=new FinalFake();fake.brokenAccounts=true;
        ResetPipeline p=new ResetPipeline(fake,x->{},Set.of());p.step(17,"ko-KR","SM-S938N");
        assertEquals(-1,p.finalAccountCount);assertFalse(p.warnings.isEmpty());
        assertFalse(p.summaryText.contains("남은 비삼성 계정: 0개"));
    }

}
