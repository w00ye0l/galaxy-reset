package com.forholiday.galaxyreset;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Android-independent V6 port. Only Bridge performs privileged operations. */
final class ResetPipeline {
    static final String SELF = "com.forholiday.galaxyreset";
    static final String KNOX_CONFIGURE = "com.sec.knox.kccagent";
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    static final String LANGUAGE = "com.forholiday.language";
    static final String LOCARORA = "com.locarora.app";
    static final String TMP = "/data/local/tmp/galaxy-reset/";
    static final String LAUNCHER = "com.sec.android.app.launcher/.activities.LauncherActivity";
    static final String[] LABELS = {"사용자 설치 앱 삭제", "계정 삭제", "유지 앱 데이터 초기화", "e-SIM 프로필 삭제", "내부 저장소 초기화", "로그·캐시 정리", "갤러리 휴지통 정리", "미디어 스토어 갱신", "썸네일 잔여물 제거", "언어 설정", "카메라 설정 초기화", "내비게이션 바 초기화", "글자 크기 초기화", "필수 앱 설치·버전 확인", "홈 독 앱 배치", "런처 초기화", "배경화면 적용", "최종 검증", "최근 앱 목록 제거"};
    static final Set<String> KEEP = Set.of(SELF, SHIZUKU, LANGUAGE, LOCARORA, KNOX_CONFIGURE, "com.sec.android.app.popupcalculator", "com.nhn.android.nmap", "com.alphainventor.filemanager", "com.sec.android.app.sbrowser");
    static final String[] LIVE = {"com.samsung.android.wallpaper.live", "com.samsung.android.dynamiclock", "com.samsung.android.app.dressroom"};
    static final String[] FOLDERS = {"DCIM", "Pictures", "Music", "Movies", "Download"};
    static final String[] LOCALES = {"ja-JP", "en-US", "ko-KR", "zh-CN", "zh-TW"};
    record Result(int code, String output, boolean truncated) {
        Result(int code, String output) { this(code, output, false); }
    }
    static final class ConnectionLost extends IOException { ConnectionLost(String message) { super(message); } }
    interface Bridge {
        Result run(String command, int seconds) throws Exception;
        void copy(String asset, String destination) throws Exception;
    }
    interface Recovery { void save(Set<String> packages); }
    final Bridge bridge;
    final Recovery recovery;
    final Set<String> pending;
    final List<String> warnings = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    boolean manual;
    final Set<String> cleared = new HashSet<>();
    final Set<String> failedClears = new HashSet<>();
    final List<String> advisories = new ArrayList<>();
    int finalAccountCount = -1;
    boolean finalUserAppsClear;
    String summaryText = "";
    interface Work { void run() throws Exception; }
    void attempt(String label, Work work) throws Exception {
        try { work.run(); }
        catch (ConnectionLost e) { throw e; }
        catch (Exception e) { warnings.add(label + " · " + (e.getMessage() == null ? "실행 실패" : e.getMessage())); }
    }
    ResetPipeline(Bridge bridge, Recovery recovery, Set<String> pending) {
        this.bridge = bridge; this.recovery = recovery; this.pending = new LinkedHashSet<>(pending);
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static String series(String model) {
        for (int i = 0; i < 4; i++) if (model.contains("S9" + (i + 1))) return "S" + (23 + i);
        return "S24";
    }
    static List<String> locales(String selected) {
        if (!Arrays.asList(LOCALES).contains(selected)) throw new IllegalArgumentException("지원하지 않는 언어");
        List<String> result = new ArrayList<>(); result.add(selected);
        for (String tag : List.of("ko-KR", "en-US", "ja-JP")) if (!tag.equals(selected)) result.add(tag);
        return result;
    }
    Result raw(String command, int seconds) throws Exception { return bridge.run(command, seconds); }
    Result checked(String command, int seconds) throws Exception {
        Result r = raw(command, seconds);
        if (r.code != 0 || r.truncated || r.output.contains("SecurityException") || r.output.contains("Error:") ||
            r.output.contains("Exception") || r.output.contains("Failure") || r.output.contains("Failed"))
            warnings.add("명령 실패/검증 필요: " + command.split(" ", 3)[0] + " (코드 " + r.code + ")");
        return r;
    }
    Result required(String command) throws Exception {
        return required(command, 120);
    }
    Result required(String command, int seconds) throws Exception {
        Result result = raw(command, seconds);
        if (result.code != 0 || result.truncated || result.output.contains("SecurityException") || result.output.contains("Error:") ||
            (command.startsWith("dumpsys") && result.output.trim().isEmpty())) throw new IOException("조회 실패: " + command);
        return result;
    }
    boolean installed(String pkg) throws Exception {
        Result r = required("pm list packages --user 0 " + quote(pkg));
        return Arrays.asList(r.output.split("\\R")).contains("package:" + pkg);
    }
    private record PackageState(Map<String, Long> versions, Set<String> enabled) {}

    private PackageState readPackageState() throws Exception {
        try {
            Map<String, Long> versions = new HashMap<>();
            Pattern version = Pattern.compile("package:([A-Za-z0-9_.]+)\\s+versionCode:(\\d+)");
            for (String line : required("pm list packages --show-versioncode --user 0").output.split("\\R")) {
                if (line.isBlank()) continue;
                Matcher entry = version.matcher(line.strip());
                if (!entry.matches() || versions.put(entry.group(1), Long.parseLong(entry.group(2))) != null)
                    throw new IOException("앱 버전 목록 형식 확인 필요");
            }
            Set<String> enabled = new HashSet<>();
            for (String line : required("pm list packages -e --user 0").output.split("\\R")) {
                if (line.isBlank()) continue;
                if (!line.matches("package:[A-Za-z0-9_.]+")) throw new IOException("활성 앱 목록 형식 확인 필요");
                enabled.add(line.substring("package:".length()));
            }
            if (!versions.containsKey(SELF) || enabled.isEmpty() || !versions.keySet().containsAll(enabled))
                throw new IOException("설치·활성 앱 목록 확인 필요");
            return new PackageState(versions, enabled);
        } catch (ConnectionLost e) {
            throw e;
        } catch (IOException | NumberFormatException e) {
            return null; // Keep per-package checks when the device cannot supply a complete inventory.
        }
    }

    private boolean packageCurrent(PackageState state, String pkg, long minimum) throws Exception {
        return state == null ? installed(pkg) && packageVersion(pkg) >= minimum
            : state.versions().getOrDefault(pkg, -1L) >= minimum;
    }
    long packageVersion(String pkg) throws Exception {
        // Full dumpsys output can exceed the Binder output cap (66 KB on a field S23).
        // Query only installed package/version and require an exact package match.
        String output=required("pm list packages --show-versioncode --user 0 "+quote(pkg)).output;
        Matcher version=Pattern.compile("(?m)^package:"+Pattern.quote(pkg)+"[ \\t]+versionCode:(\\d+)[ \\t]*$").matcher(output);
        if(!version.find())throw new IOException("설치 버전 조회 실패: "+pkg);
        return Long.parseLong(version.group(1));
    }
    void ensureEnabled(String pkg) throws Exception {
        String query="pm list packages -e --user 0 "+quote(pkg);
        if(Arrays.asList(required(query).output.split("\\R")).contains("package:"+pkg))return;
        // Changing our own package state can kill the running reset service.
        // Shizuku is also live infrastructure: only inspect it during a phone reset.
        if(pkg.equals(SELF)||pkg.equals(SHIZUKU))
            throw new IOException("실행 기반 앱 활성 상태 확인 필요: "+pkg);
        Result enabled=raw("pm enable --user 0 "+quote(pkg),30);
        if(enabled.code!=0||enabled.truncated||enabled.output.contains("Exception")||enabled.output.contains("Error:"))
            throw new IOException("필수 앱 활성화 실패: "+pkg);
        if(!Arrays.asList(required(query).output.split("\\R")).contains("package:"+pkg))
            throw new IOException("필수 앱 활성 상태 확인 실패: "+pkg);
    }
    void nmapInstallSuccess(Result result) throws IOException {
        if(result.code!=0||result.truncated||!result.output.contains("Success")||result.output.contains("Failure"))
            throw new IOException("네이버 지도 분할 APK 설치 실패: "+result.output.substring(0,Math.min(180,result.output.length())));
    }
    void prepareNmap() throws Exception {
        prepareNmap(null);
    }
    private void prepareNmap(PackageState state) throws Exception {
        String pkg="com.nhn.android.nmap";
        if(!packageCurrent(state,pkg,BuildConfig.NMAP_VERSION_CODE)){
            String session=null;boolean committed=false;
            try{
                for(String asset:BuildConfig.NMAP_APKS)bridge.copy(asset,TMP+asset);
                Result created=required("pm install-create -r --user 0");
                Matcher id=Pattern.compile("Success: created install session \\[(\\d+)\\]").matcher(created.output);
                if(!id.find())throw new IOException("네이버 지도 설치 세션 생성 실패");
                session=id.group(1);
                for(String asset:BuildConfig.NMAP_APKS)
                    nmapInstallSuccess(raw("pm install-write "+session+" "+quote(asset)+" "+quote(TMP+asset),300));
                nmapInstallSuccess(raw("pm install-commit "+session,300));committed=true;
            }finally{
                if(session!=null&&!committed)try{raw("pm install-abandon "+session,30);}catch(Exception ignored){}
                for(String asset:BuildConfig.NMAP_APKS)try{raw("rm -f "+quote(TMP+asset),30);}catch(Exception ignored){}
            }
            state = null;
            if(!packageCurrent(null,pkg,BuildConfig.NMAP_VERSION_CODE))throw new IOException("네이버 지도 설치 버전 확인 실패");
        }
        if (state == null || !state.enabled().contains(pkg)) ensureEnabled(pkg);
        notes.add("네이버 지도 설치·버전·활성 상태 확인 완료");
    }
    void commands(String... commands) throws Exception { for (String c : commands) checked(c, 120); }
    void clear(String... packages) throws Exception {
        for (String pkg : packages) attempt("앱 데이터 초기화 실패: " + pkg, () -> {
            if (pkg.equals("com.alphainventor.filemanager")) {
                notes.add("파일 관리자 SMB 연결·앱 설정 보존 · 데이터 삭제 제외");
                return;
            }
            if (cleared.contains(pkg)) return;
            failedClears.add(pkg);
            if (!installed(pkg)) { failedClears.remove(pkg); return; }
            Result result = raw("pm clear --user 0 " + quote(pkg), 120);
            if (result.code != 0 || result.truncated || !result.output.trim().equals("Success"))
                { failedClears.add(pkg); throw new IOException("삭제 결과 확인 필요"); }
            cleared.add(pkg);failedClears.remove(pkg);
        });
    }
    private void clearCleanupPackages(List<String> packages) throws Exception {
        Set<String> targets = new LinkedHashSet<>(packages);
        targets.removeAll(cleared);
        if (targets.isEmpty()) return;

        Set<String> installed = new HashSet<>();
        try {
            for (String line : required("command -v timeout >/dev/null && pm list packages --user 0").output.split("\\R")) {
                if (line.isBlank()) continue;
                if (!line.matches("package:[A-Za-z0-9_.]+")) throw new IOException("잘못된 앱 목록");
                installed.add(line.substring("package:".length()));
            }
            if (installed.isEmpty()) throw new IOException("설치 앱 목록이 비어 있습니다.");
        } catch (ConnectionLost e) {
            throw e;
        } catch (IOException e) {
            clear(targets.toArray(new String[0]));
            return;
        }
        for (String pkg : targets) {
            if (!installed.contains(pkg)) failedClears.remove(pkg);
        }
        targets.retainAll(installed);
        if (targets.isEmpty()) return;

        // Keep package clears sequential. Report each result so a failed clear cannot be
        // mistaken for success or prevent later packages from being cleared.
        StringJoiner arguments = new StringJoiner(" ");
        for (String pkg : targets) arguments.add(quote(pkg));
        String command = "for pkg in " + arguments + "; do "
            + "if result=$(timeout 120 pm clear --user 0 \"$pkg\" 2>&1) && [ \"$result\" = Success ]; then "
            + "printf 'CLEARED:%s\\n' \"$pkg\"; else printf 'CLEAR_FAILED:%s\\n' \"$pkg\"; fi; done";
        failedClears.addAll(targets);
        Result batch = raw(command, 600);
        Set<String> confirmed = new HashSet<>();
        Set<String> failed = new HashSet<>();
        if (batch.code == 0 && !batch.truncated) {
            for (String line : batch.output.split("\\R")) {
                if (line.startsWith("CLEARED:")) confirmed.add(line.substring("CLEARED:".length()));
                if (line.startsWith("CLEAR_FAILED:")) failed.add(line.substring("CLEAR_FAILED:".length()));
            }
        }
        for (String pkg : targets) {
            if (confirmed.contains(pkg) && !failed.contains(pkg)) {
                cleared.add(pkg);
                failedClears.remove(pkg);
            } else {
                warnings.add("앱 데이터 초기화 실패: " + pkg + " · 삭제 결과 확인 필요");
            }
        }
    }
    Result dex(String file, String main, String... args) throws Exception {
        bridge.copy(file, TMP + file);
        StringBuilder command = new StringBuilder("CLASSPATH=" + quote(TMP + file) + " app_process /system/bin " + main);
        for (String arg : args) command.append(' ').append(quote(arg));
        return checked(command.toString(), 300);
    }
    void success(Result r, String token, String description) {
        if (!r.output.contains(token) || r.output.contains("FAIL:")) warnings.add(description);
    }
    static Set<String> widgetProviders(String dump) {
        Set<String> result = new LinkedHashSet<>(); boolean widgets = false;
        Pattern pattern = Pattern.compile("cmp:ComponentInfo\\{([^/]+)/");
        for (String line : dump.split("\\R")) {
            String stripped = line.trim();
            if (stripped.startsWith("Widgets:")) { widgets = true; continue; }
            if (stripped.matches("[A-Za-z][A-Za-z ]*:")) { widgets = false; continue; }
            if (widgets && line.contains("provider=ProviderId")) {
                Matcher match = pattern.matcher(line);
                if (match.find() && match.group(1).matches("[A-Za-z0-9_.]+") && !KEEP.contains(match.group(1))) result.add(match.group(1));
            }
        }
        return result;
    }
    static int nonSamsungAccounts(String dump) {
        return accountTypes(dump).stream().mapToInt(type -> !type.startsWith("com.samsung") && !type.startsWith("com.osp.app.signin") ? 1 : 0).sum();
    }
    static int naverAccounts(String dump) {
        return (int) accountTypes(dump).stream().filter("com.nhn.android.naveraccount"::equals).count();
    }
    static List<String> accountTypes(String dump) {
        return AccountPreparationPolicy.accountList(dump);
    }
    // Keep account validation below the Binder output cap and omit account names.
    static final String ACCOUNT_QUERY = "set -o pipefail; dumpsys account | awk '"
        + "BEGIN { owner = 1 } "
        + "/^[[:space:]]*Account event dump:/ { events = 1 } events { next } "
        + "/^[[:space:]]*(Permission Denial|SecurityException|DUMP TIMEOUT|Error:)/ { exit 1 } "
        + "/^[[:space:]]*User UserInfo\\{[0-9]+:/ { "
        + "users = 1; owner = ($0 ~ /^[[:space:]]*User UserInfo\\{0:/); "
        + "if (owner) { found = 1; print \"User UserInfo{0:\" } next } "
        + "owner && /^[[:space:]]*Accounts:[[:space:]]*[0-9]+[[:space:]]*$/ { print; next } "
        + "owner && /^[[:space:]]*Account \\{/ { "
        + "if (!match($0, /,[[:space:]]*type=[A-Za-z0-9_.-]+[[:space:]]*\\}[[:space:]]*$/)) exit 1; "
        + "print \"Account {name=redacted\" substr($0, RSTART) } "
        + "END { if (users && !found) exit 1 }'";
    String accountInventory() throws Exception {
        try { return required(ACCOUNT_QUERY).output; }
        catch (ConnectionLost e) { throw e; }
        catch (IOException e) { throw new IOException("조회 실패: dumpsys account", e); }
    }
    static final String WIDGET_QUERY = "dump=$(dumpsys appwidget) || exit $?; printf '%s\\n' \"$dump\" | sed -n '/^[[:space:]]*Widgets:/,/^[[:space:]]*Hosts:/p'";
    Set<String> queryWidgetProviders() throws Exception {
        Result result = required(WIDGET_QUERY, 3);
        if (!result.output.contains("Widgets:") || !result.output.contains("Hosts:"))
            throw new IOException("위젯 영역 조회 실패");
        return widgetProviders(result.output);
    }
    static int remainingUserFiles(String listing) {
        int count = 0;
        for (String path : listing.split("\\R")) {
            if (path.trim().isEmpty() || path.endsWith("/.nomedia")) continue;
            // MediaProvider regenerates these identifiers; actual thumbnail images still count.
            if (path.contains("/.thumbnails/") && path.endsWith("/.database_uuid")) continue;
            count++;
        }
        return count;
    }
    void pause(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
    private void awaitMediaProvider() throws Exception {
        // A zero-row query checks the same provider and volume without reading user files.
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            Result response = raw("content query --user 0 --uri content://media/external/file "
                + "--projection _id --where '1=0'", 2);
            if (response.code == 0 && !response.truncated && response.output.trim().equals("No result found.")) return;
            if (System.nanoTime() >= deadline) break;
            pause(200);
        } while (true);
        warnings.add("미디어 저장소 응답 확인 필요");
    }

    private void deleteMediaRows() throws Exception {
        List<String> endpoints = List.of("images/media", "video/media", "audio/media", "file");
        String command = "command -v timeout >/dev/null || exit 127; for path in "
            + String.join(" ", endpoints)
            + "; do output=$(timeout 120 content delete --user 0 --uri \"content://media/external/$path\" 2>&1); "
            + "status=$?; case \"$output\" in *SecurityException*|*Error:*|*Exception*|*Failure*|*Failed*) status=1;; esac; "
            + "printf '%s:%s\\n' \"$path\" \"$status\"; done";
        Result result = raw(command, 500);
        if (result.code == 127 && !result.truncated && result.output.isBlank()) {
            for (String path : endpoints) checked("content delete --user 0 --uri content://media/external/" + path, 120);
            return;
        }
        Set<String> completed = new HashSet<>(Arrays.asList(result.output.split("\\R")));
        for (String path : endpoints) {
            if (result.code != 0 || result.truncated || !completed.contains(path + ":0"))
                warnings.add("미디어 스토어 삭제 결과 확인 필요: " + path);
        }
    }
    void wipeInternalStorage() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            // rm can report a directory recreated by a running system app. Verify files
            // independently rather than retaining that command warning as a reset failure.
            for (String command : List.of("rm -rf /storage/emulated/0/*",
                    "rm -rf /storage/emulated/0/.[!.]* /storage/emulated/0/..?*")) {
                Result deletion = raw(command, 300);
                if (deletion.code == 124) throw new IOException("내부 저장소 삭제 시간 초과 · 재확인 필요");
            }
            Result listing = required("find /storage/emulated/0 -type f");
            int remaining = remainingUserFiles(listing.output);
            if (remaining == 0) {
                notes.add(attempt == 0 ? "내부 저장소 잔여 파일 0개 확인" : "내부 저장소 자동 재시도 후 잔여 파일 0개 확인");
                return;
            }
            if (attempt == 0) { pause(500); continue; }
            manual = true;
            warnings.add("내부 저장소 잔여 파일 " + remaining + "개 · 자동 재시도 후에도 남음 · 수동 확인 필요");
        }
    }
    private void startLauncher() throws Exception {
        if (!required("am start -W --user 0 -n " + LAUNCHER, 15).output.contains("Status: ok"))
            throw new IOException("삼성 런처 시작 확인 필요");
    }
    private Set<String> awaitWidgets(boolean removing) throws Exception {
        long started = System.nanoTime();
        long deadline = started + (removing ? 12_000_000_000L : 4_000_000_000L);
        Set<String> previous = null;
        long stableSince = 0;
        while (true) {
            Set<String> current = queryWidgetProviders();
            long now = System.nanoTime();
            if (!current.equals(previous)) stableSince = now;
            // A disabled provider disappears from dumpsys before Samsung Home has
            // removed its saved widget slot. Keep providers disabled during that cleanup.
            if (current.isEmpty() == removing && now - stableSince >= 1_000_000_000L
                    && (!removing || now - started >= 8_000_000_000L)) return current;
            if (now >= deadline) {
                // An empty list just after launch can precede delayed default widget registration.
                if (!removing) return current;
                throw new IOException("런처 위젯 제거 완료를 확인하지 못했습니다.");
            }
            previous = current;
            pause(250);
        }
    }
    private void resetLauncher() throws Exception {
        clear("com.sec.android.app.launcher");
        if (!cleared.contains("com.sec.android.app.launcher"))
            throw new IOException("삼성 런처 데이터 초기화 확인 필요");
        startLauncher();
        clearLauncherSearchBar();
        Set<String> providers = awaitWidgets(false);
        if (providers.isEmpty()) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                disableTemporarily(providers);
                if (attempt == 1) {
                    // Rebuild the default layout while the rebound widget is unavailable.
                    cleared.remove("com.sec.android.app.launcher");
                    clear("com.sec.android.app.launcher");
                    if (!cleared.contains("com.sec.android.app.launcher"))
                        throw new IOException("삼성 런처 데이터 재초기화 확인 필요");
                } else {
                    required("am force-stop com.sec.android.app.launcher", 15);
                }
                startLauncher();
                required("input keyevent KEYCODE_HOME", 10);
                awaitWidgets(true);
            } finally {
                restorePending();
            }
            if (attempt == 1) clearLauncherSearchBar();
            providers = queryWidgetProviders();
            if (providers.isEmpty()) return;
        }
        throw new IOException("위젯 앱 복구 후 홈 위젯이 남아 있습니다.");
    }
    private void clearLauncherSearchBar() throws Exception {
        String failure = "삼성 홈 검색 바 설정 확인 필요";
        for (int attempt = 0; attempt < 2; attempt++) {
            Result result = runHomeTool(" --clear-search-bar", 30);
            List<String> lines = Arrays.asList(result.output.split("\\R"));
            if (result.code == 0 && !result.truncated && lines.contains("SUCCESS:HOME_SEARCH_CHECKED")) return;
            failure = lines.stream().filter(line -> line.startsWith("FAIL:"))
                .map(line -> line.substring(5)).findFirst().orElse(failure);
            if (attempt == 0) pause(500);
        }
        throw new IOException(failure);
    }
    void disableTemporarily(Collection<String> packages) throws Exception {
        for (String pkg : packages) {
            // Persist before sending the command: also covers disconnect during disable.
            pending.add(pkg); recovery.save(pending);
            checked("pm disable-user --user 0 " + quote(pkg), 120);
        }
    }
    void restorePending() throws Exception {
        Exception lost = null;
        for (String pkg : new ArrayList<>(pending)) {
            try {
                Result r = raw("pm enable --user 0 " + quote(pkg), 120);
                if (r.code == 0 && !r.output.contains("Exception") && !r.output.contains("Error")) {
                    pending.remove(pkg); recovery.save(pending);
                } else warnings.add("재활성화 필요: " + pkg);
            } catch (Exception e) { lost = e; }
        }
        if (lost != null) throw lost;
        if (!pending.isEmpty()) throw new IOException("앱 재활성화 실패 · 다시 연결 후 재시도 필요");
    }
    String configureScreenTimeout() throws Exception {
        try {
            required("settings --user 0 put system screen_off_timeout 600000");
            if (!required("settings --user 0 get system screen_off_timeout").output.trim().equals("600000"))
                throw new IOException("설정값 불일치");
            return "화면 꺼짐 시간: 10분 설정 확인";
        } catch (ConnectionLost e) { throw e; }
        catch (Exception e) { return "화면 꺼짐 시간: 10분 적용 확인 필요 · 초기화는 계속 진행"; }
    }
    void step(int index, String locale, String model) throws Exception {
        warnings.clear(); notes.clear(); manual = false;
        switch (index) {
            case 9 -> success(dex("locale_changer.dex", "LocaleChanger", locales(locale).toArray(new String[0])), "SUCCESS", "언어 변경 확인 필요");
            case 1 -> {
                String before = accountInventory();
                if (nonSamsungAccounts(before) == 0) { clearAccountSessions(); notes.add("비삼성 계정 없음 · 제거 생략"); break; }
                if (nonSamsungAccounts(before)>0) {
                    attempt("계정 인증 서비스 준비 실패 · 제거 계속", () -> {
                        Result prepared = dex("account_preparer.dex", "AccountPreparer", "--prepare");
                        if (prepared.code != 0 || prepared.truncated || !prepared.output.contains("SUCCESS:AUTHENTICATORS_READY"))
                            throw new IOException("일부 인증 서비스 활성화 확인 필요");
                        pause(1000);
                    });
                }
                try { dex("account_remover.dex", "AccountRemover"); }
                catch (ConnectionLost e) { throw e; }
                catch (Exception ignored) { notes.add("백그라운드 계정 제거 재확인"); }
                if (naverAccounts(accountInventory()) > 0) {
                    try {
                        new NaverAccountCleanup(this).remove();
                        notes.add("네이버 계정·인증 정보 기기에서 제거 확인 · 계정 탈퇴 아님");
                    } catch (ConnectionLost e) { throw e; }
                    catch (Exception e) { warnings.add("네이버 계정 제거 확인 필요 · 화면을 켜고 잠금 해제 후 재시도하세요."); }
                }
                clearAccountSessions();
                int remaining = nonSamsungAccounts(accountInventory());
                if (remaining == 0) {
                    // The final inventory, including NAVER, proves successful fallback removal.
                    warnings.clear(); notes.add("비삼성 계정 0개 확인 · 삼성 계정 보존");
                } else {
                    manual = true; warnings.add("비삼성 계정 삭제 실패 · 남은 계정 " + remaining + "개, 설정에서 수동 확인 필요");
                }
            }
            case 3 -> {
                Result result = dex("esim_manager.dex", "EsimManager", "delete-all");
                boolean verified = result.code == 0 && !result.truncated && !result.output.contains("FAIL:")
                    && (result.output.contains("SUCCESS:NO_ESIM:") || result.output.contains("SUCCESS:ALL_ESIM_DELETED:"));
                if (!verified) {
                    manual = true; warnings.add("e-SIM 잔여 프로필 확인 필요 · SIM 관리자에서 확인하세요.");
                    commands("am start -a android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS");
                }
            }
            case 2 -> {
                notes.add("파일 관리자 SMB 연결·앱 설정 보존 · 데이터 삭제 제외");
                clear("com.google.android.googlequicksearchbox", "com.android.chrome", "com.google.android.youtube", "com.google.android.gm",
                "com.google.android.apps.maps", "com.google.android.apps.docs", "com.google.android.calendar", "com.google.android.apps.photos", "com.sec.android.app.sbrowser", "com.nhn.android.nmap",
                "com.sec.android.app.popupcalculator", "com.google.android.apps.tachyon", LOCARORA);
            }
            case 0 -> {
                Result r = required("pm list packages --user 0 -3");
                for (String line : r.output.split("\\R")) {
                    if (!line.startsWith("package:")) continue;
                    String pkg = line.substring(8).trim();
                    if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IOException("잘못된 패키지 목록");
                    if(pkg.equals(KNOX_CONFIGURE))notes.add("Knox Configure 관리 앱 보존 · 관리자 등록 유지");
                    if (!KEEP.contains(pkg)) attempt("앱 삭제 실패: " + pkg, () -> success(checked("pm uninstall --user 0 " + quote(pkg), 120), "Success", "앱 삭제 실패: " + pkg));
                }
            }
            case 4 -> wipeInternalStorage();
            case 5 -> {
                Result result;
                try { result = dex("content_cleaner.dex", "ContentCleaner"); }
                catch (ConnectionLost e) { throw e; }
                catch (Exception e) { result = new Result(1, "PERMISSION_DENIED:CONTACTS\nPERMISSION_DENIED:SMS"); }
                String output = result.output;
                List<String> packages = new ArrayList<>();
                if (output.contains("PERMISSION_DENIED:CALL_LOG") || output.contains("PERMISSION_DENIED:CONTACTS") ||
                    output.contains("FALLBACK:CALL_LOG") || output.contains("FALLBACK:CONTACTS")) packages.add("com.android.providers.contacts");
                if (output.contains("PERMISSION_DENIED:SMS") || output.contains("FALLBACK:SMS")) packages.add("com.android.providers.telephony");
                if (output.contains("FAIL:") || (!output.contains("SUCCESS:") && !output.contains("PARTIAL:") && !output.contains("PERMISSION_DENIED:") && !output.contains("FALLBACK:"))) warnings.add("통화/SMS/주소록 삭제 결과 확인 필요");
                packages.addAll(List.of("com.samsung.android.messaging", "com.samsung.android.providers.contacts", "com.samsung.android.app.contacts",
                    "com.samsung.android.dsms", "com.android.mms.service", "com.sec.android.gallery3d", "com.sec.android.themestore",
                    "com.sec.android.app.vepreload", "com.samsung.android.app.clipboardedge", "com.samsung.android.honeyboard"));
                clearCleanupPackages(packages);
            }
            case 6 -> {
                commands("am force-stop com.sec.android.gallery3d", "am force-stop com.sec.android.app.myfiles");
                List<String> paths = List.of(
                    "/sdcard/Android/data/com.sec.android.gallery3d/files/Trash", "/sdcard/Android/data/com.sec.android.gallery3d/files/.Trash",
                    "/sdcard/Android/data/com.sec.android.gallery3d/cache", "/sdcard/Android/data/com.sec.android.app.myfiles/files/Trash",
                    "/sdcard/Android/data/com.sec.android.app.myfiles/files/.Trash", "/sdcard/Android/data/com.sec.android.app.vepreload/files/Trash",
                    "/storage/emulated/0/Android/.Trash", "/sdcard/.Trash", "/sdcard/.Trash-0", "/sdcard/Recycle",
                    "/sdcard/Android/data/com.sec.android.gallery3d/files/.thumbnails", "/sdcard/Android/data/com.android.chrome/cache",
                    "/sdcard/Android/data/dji.mimo/cache", "/sdcard/Android/data/com.nhn.android.nmap/cache");
                checked("rm -rf -- " + String.join(" ", paths.stream().map(ResetPipeline::quote).toArray(String[]::new)), 600);
                clearCleanupPackages(List.of("com.sec.android.gallery3d", "com.sec.android.app.myfiles", "com.samsung.android.providers.media",
                    "com.samsung.android.providers.trash", "com.android.providers.media", "com.google.android.providers.media.module"));
                Result r = raw("am broadcast -a android.intent.action.MEDIA_MOUNTED -d file:///sdcard", 120);
                if (r.code != 0 || r.output.contains("SecurityException"))
                    commands("am force-stop com.android.providers.media", "am force-stop com.google.android.providers.media.module");
                awaitMediaProvider();
            }
            case 7 -> deleteMediaRows();
            case 8 -> { for (String folder : FOLDERS) checked("rm -rf /sdcard/" + folder + "/.thumbnails", 120); }
            case 10 -> clear("com.sec.android.app.camera", "com.samsung.android.app.cameraassistant");
            case 11 -> {
                for (String overlay : List.of("com.samsung.internal.systemui.navbar.sec_gestural", "com.samsung.internal.systemui.navbar.sec_gestural_no_hint",
                    "com.samsung.internal.systemui.navbar.gestural_no_hint", "com.android.internal.systemui.navbar.gestural",
                    "com.android.internal.systemui.navbar.transparent", "com.android.internal.systemui.navbar.threebutton"))
                    checked("cmd overlay disable --user 0 " + overlay, 120);
                settings("global navigation_bar_gesture_while_hidden 0", "secure navigation_mode 0", "global navigationbar_key_order 0",
                    "secure navigationbar_key_order 0", "global navigation_bar_gesture_hint 1");
            }
            case 12 -> settings("system font_scale 1.0", "system device_font_scale 1.0", "global font_size 2", "global bold_text 0",
                "global font_style_index 0", "secure enhanced_comfort_font_value 0");
            case 14 -> {
                for (String pkg : List.of("com.samsung.android.dialer", "com.samsung.android.messaging", "com.sec.android.app.sbrowser", "com.sec.android.app.camera")) {
                    if (pkg.equals("com.sec.android.app.sbrowser") && !installed(pkg)) {
                        notes.add("삼성 인터넷 없음 · 선택 항목이므로 건너뜀");
                        continue;
                    }
                    Result r = raw("cmd package install-existing --user 0 " + quote(pkg), 120);
                    if (r.code != 0 || r.output.contains("NameNotFoundException") || r.output.contains("doesn't exist")) { manual = true; warnings.add("홈 독 앱 누락 · 스토어에서 설치 필요: " + pkg); continue; }
                    commands("pm enable --user 0 " + quote(pkg));
                }
            }
            case 15 -> resetLauncher();
            case 17 -> verifyFinalState(model);
            case 16 -> wallpaper(model);
            case 13 -> {
                PackageState state = readPackageState();
                attempt("네이버 지도 준비 실패", () -> prepareNmap(state));
                attempt("파일 관리자 설치·버전 확인 실패", () -> prepareFileManager(state));
                attempt("언어 앱 설치·권한 설정 실패", () -> prepareLanguageApp(state));
                attempt("LOCARORA 설치·버전 확인 실패", () -> prepareLocaroraApp(state));
                attempt("초기화 앱 설정 권한 확인 실패", () -> commands("pm grant --user 0 " + SELF + " android.permission.WRITE_SECURE_SETTINGS"));
                for (String pkg : List.of(SELF, SHIZUKU)) attempt("실행 기반 앱 확인 실패: " + pkg, () -> {
                    if (state != null && state.enabled().contains(pkg)) return;
                    if (!installed(pkg)) throw new IOException("필수 앱 없음");
                    ensureEnabled(pkg);
                });
            }
            case 18 -> {
                Result recents = dex("recent_tasks_cleaner.dex", "RecentTasksCleaner");
                if (recents.code != 0 || recents.truncated || recents.output.contains("FAIL:")
                        || !Arrays.asList(recents.output.split("\\R")).contains("SUCCESS:RECENTS_EMPTY"))
                    warnings.add("최근 앱 목록이 비어 있는지 확인 필요");
                placeHomeIcons();
            }
            default -> throw new IllegalArgumentException("잘못된 단계");
        }
    }
    void prepareLanguageApp() throws Exception {
        prepareLanguageApp(null);
    }
    private void prepareLanguageApp(PackageState state) throws Exception {
        prepareBundledApp(state, LANGUAGE, "language.apk", BuildConfig.LANGUAGE_VERSION_CODE);
        required("pm grant --user 0 " + LANGUAGE + " android.permission.CHANGE_CONFIGURATION");
        required("cmd appops set --user 0 " + LANGUAGE + " WRITE_SETTINGS allow");
        verifyLanguagePermission();
        notes.add("Language 설치·언어 변경 권한 확인 완료");
    }

    void prepareLocaroraApp() throws Exception {
        prepareLocaroraApp(null);
    }
    private void prepareLocaroraApp(PackageState state) throws Exception {
        prepareBundledApp(state, LOCARORA, "locarora.apk", BuildConfig.LOCARORA_VERSION_CODE);
        notes.add("LOCARORA 설치·버전·활성 상태 확인 완료");
    }

    private void prepareBundledApp(PackageState state, String pkg, String asset, long minimumVersion) throws Exception {
        if (!packageCurrent(state, pkg, minimumVersion)) {
            String path = TMP + asset;
            try {
                bridge.copy(asset, path);
                Result result = raw("pm install -r --user 0 " + quote(path), 120);
                if (result.truncated || (!result.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE")
                        && (result.code != 0 || !result.output.trim().equals("Success")))) {
                    String reason = result.output.strip();
                    throw new IOException(reason.isEmpty() ? "앱 설치 결과 확인 필요" : reason.substring(0, Math.min(160, reason.length())));
                }
            } finally {
                try { raw("rm -f " + quote(path), 15); } catch (Exception ignored) {}
            }
            state = null;
            if (!packageCurrent(null, pkg, minimumVersion)) throw new IOException("앱 설치·버전 확인 필요: " + pkg);
        }
        if (state == null || !state.enabled().contains(pkg)) ensureEnabled(pkg);
    }

    private void verifyLanguagePermission() throws Exception {
        String permission = required("dumpsys package " + LANGUAGE
            + " | grep -F 'android.permission.CHANGE_CONFIGURATION: granted=true'").output;
        String appOp = required("cmd appops get --user 0 " + LANGUAGE + " WRITE_SETTINGS").output;
        if (!permission.contains("granted=true") || !appOp.contains("WRITE_SETTINGS: allow"))
            throw new IOException("언어 변경 권한 확인 필요");
    }

    private void placeHomeIcons() throws Exception {
        try {
            if (!installed(LANGUAGE)) throw new IOException("Language 설치 후 다시 시도하세요.");
            if (!installed(LOCARORA)) throw new IOException("LOCARORA 설치 후 다시 시도하세요.");
            Result result = runHomeTool("", 180);
            List<String> output = Arrays.asList(result.output.split("\\R"));
            if (result.code != 0 || result.truncated || result.output.contains("FAIL:")
                    || !output.contains("SUCCESS:MAIN_HOME_LAYOUT")) {
                String reason = output.stream().filter(line -> line.startsWith("FAIL:"))
                    .map(line -> line.substring(5)).findFirst().orElse("삼성 런처 상태와 홈 화면 잠금 여부를 확인하세요.");
                throw new IOException(reason);
            }
            notes.add("메인 홈 1페이지 확인 · 갤러리 / Play 스토어 / LOCARORA / Language");
        } catch (ConnectionLost e) {
            throw e;
        } catch (Exception e) {
            manual = true;
            warnings.add("앱 홈 화면 배치 확인 필요 · " + e.getMessage());
        }
    }
    private Result runHomeTool(String arguments, int timeout) throws Exception {
        String apk = Arrays.stream(required("pm path --user 0 " + SELF).output.split("\\R"))
            .filter(line -> line.startsWith("package:/data/app/") && line.endsWith("/base.apk"))
            .map(line -> line.substring("package:".length())).findFirst()
            .orElseThrow(() -> new IOException("배치 도구 경로 확인 필요"));
        return raw("CLASSPATH=" + quote(apk)
            + " app_process /system/bin com.forholiday.galaxyreset.LanguageHomePlacement" + arguments, timeout);
    }
    void clearAccountSessions() throws Exception {
        int failures = warnings.size();
        clear("com.nhn.android.nmap", "com.google.android.apps.tachyon");
        if (warnings.size() > failures) throw new IOException("로그인·저장 데이터 초기화 실패");
        if (cleared.contains("com.nhn.android.nmap")) notes.add("네이버 지도 로그인 세션·저장 데이터 초기화 확인");
        if (cleared.contains("com.google.android.apps.tachyon")) notes.add("Google Meet 로그인 세션·저장 데이터 초기화 확인");
    }
    void prepareFileManager() throws Exception {
        prepareFileManager(null);
    }
    private void prepareFileManager(PackageState state) throws Exception {
        String pkg = "com.alphainventor.filemanager", asset = "filemanager.apk";
        if (!packageCurrent(state, pkg, BuildConfig.FILEMANAGER_VERSION_CODE)) {
            try {
                bridge.copy(asset, TMP + asset);
                Result installed = raw("pm install -r --user 0 " + quote(TMP + asset), 300);
                if (!installed.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") &&
                    (installed.code != 0 || installed.truncated || !installed.output.contains("Success")))
                    throw new IOException("파일 관리자 설치 결과 확인 필요");
            } finally { try { raw("rm -f " + quote(TMP + asset), 30); } catch (Exception ignored) {} }
            state = null;
            if (!packageCurrent(null, pkg, BuildConfig.FILEMANAGER_VERSION_CODE))
                throw new IOException("파일 관리자 설치·버전 확인 실패");
        }
        try { if (state == null || !state.enabled().contains(pkg)) ensureEnabled(pkg); }
        catch (ConnectionLost e) { throw e; }
        catch (Exception e) {
            String note = "파일 관리자 설치 유지 · 딥 슬립/활성 상태 확인 필요 (초기화 계속)";
            if (!advisories.contains(note)) advisories.add(note);
            notes.add(note);
        }
    }
    void verifyFinalState(String model) throws Exception {
        finalAccountCount = -1; finalUserAppsClear = false;
        StringBuilder result = new StringBuilder();
        attempt("최종 계정 조회 실패", () -> {
            finalAccountCount = nonSamsungAccounts(accountInventory());
            result.append("남은 비삼성 계정: ").append(finalAccountCount).append("개\n");
            if (finalAccountCount > 0) throw new IOException("비삼성 계정 " + finalAccountCount + "개 남음");
        });
        attempt("최종 삭제 대상 앱 확인 실패", () -> {
            String listed = required("pm list packages --user 0 -3").output;
            List<String> remaining = new ArrayList<>();
            for (String line : listed.split("\\R")) {
                if (!line.startsWith("package:")) { if (!line.isBlank()) throw new IOException("잘못된 앱 목록"); continue; }
                String pkg = line.substring(8).trim();
                if (!KEEP.contains(pkg)) remaining.add(pkg);
            }
            finalUserAppsClear = remaining.isEmpty();
            if (!finalUserAppsClear) throw new IOException("삭제 대상 앱 잔존: " + String.join(", ", remaining));
        });
        attempt("최종 언어 조회 실패", () -> result.append("실제 언어 목록: ").append(required("settings get system system_locales").output.trim()).append('\n'));
        PackageState state = readPackageState();
        for (String pkg : List.of("com.nhn.android.nmap", "com.alphainventor.filemanager", SELF, SHIZUKU, LOCARORA)) {
            attempt("필수 앱 최종 확인 실패: " + pkg, () -> {
                if (!(state == null ? installed(pkg) : state.versions().containsKey(pkg))) {
                    result.append(pkg).append(": 없음\n"); throw new IOException("필수 앱 없음");
                }
                long minimum = switch (pkg) {
                    case "com.nhn.android.nmap" -> BuildConfig.NMAP_VERSION_CODE;
                    case "com.alphainventor.filemanager" -> BuildConfig.FILEMANAGER_VERSION_CODE;
                    case SELF -> BuildConfig.VERSION_CODE;
                    case LOCARORA -> BuildConfig.LOCARORA_VERSION_CODE;
                    default -> 1086;
                };
                if ((state == null ? packageVersion(pkg) : state.versions().get(pkg)) < minimum)
                    throw new IOException("필수 앱 버전 부족");
                result.append(pkg).append(": 설치됨\n");
                if (!pkg.equals("com.alphainventor.filemanager")) {
                    boolean enabled = state == null
                        ? Arrays.asList(required("pm list packages -e --user 0 " + quote(pkg)).output.split("\\R")).contains("package:" + pkg)
                        : state.enabled().contains(pkg);
                    if (!enabled)
                        throw new IOException("필수 앱 활성 상태 확인 필요");
                }
            });
        }
        attempt("언어 앱 최종 확인 실패", () -> {
            if (!packageCurrent(state, LANGUAGE, BuildConfig.LANGUAGE_VERSION_CODE))
                throw new IOException("Language 설치·버전 확인 필요");
            verifyLanguagePermission();
            result.append(LANGUAGE).append(": 설치됨\n");
        });
        attempt("최종 배경 확인 실패", () -> {
            boolean live = liveOverride(required("dumpsys wallpaper").output);
            result.append("배경: ").append(series(model)).append(live ? " · 라이브 배경 확인 필요" : " · 라이브 오버레이 미탐지 (이미지는 화면 확인)");
            if (live) throw new IOException("라이브 배경이 남아 있음");
        });
        if (!advisories.isEmpty()) result.append("\n주의: ").append(String.join("\n", advisories));
        summaryText = result.toString();
    }
    void settings(String... entries) throws Exception { for (String entry : entries) checked("settings --user 0 put " + entry, 120); }
    void cleanLive() throws Exception {
        for (String pkg : LIVE) commands("am force-stop " + pkg);
        clear(LIVE);
    }
    static final String WALLPAPER_STATE_QUERY = "set -o pipefail; dumpsys -t 2 wallpaper | awk '"
        + "/^mLast(Lock)?Wallpaper state:/ { state = 1; print; next } "
        + "/^[^[:space:]]/ { state = 0 } "
        + "state && /^[[:space:]]+(User [[:digit:]]+: id=|mName=|mDisplayId=|mEngine=)/ { print }'";
    private static final String HOME_WALLPAPER_STATE = "mLastWallpaper state:";
    private static final String LOCK_WALLPAPER_STATE = "mLastLockWallpaper state:";
    private static final Pattern OWNER_WALLPAPER = Pattern.compile(
        "(?ms)^\\h*User 0: id=(\\d+)\\b[^\\n]*\\n(.*?)(?=^\\h*User \\d+:|\\z)");
    private static final Pattern WALLPAPER_NAME = Pattern.compile("(?m)^\\h*mName=WallpaperSetter\\h*$");
    private static final Pattern WALLPAPER_ENGINE = Pattern.compile(
        "(?m)^\\h*mDisplayId=0\\h*\\R\\h*mEngine=(?!null\\b)\\S+");
    record WallpaperSlot(long id, boolean applied) {
        boolean replaced(WallpaperSlot before) { return applied && id > 0 && (before == null || id != before.id); }
    }
    record WallpaperState(WallpaperSlot home, WallpaperSlot lock) {
        boolean replaced(WallpaperState before) {
            return home != null && lock != null && home.replaced(before.home) && lock.replaced(before.lock);
        }
    }
    static WallpaperState wallpaperState(String dump) throws IOException {
        WallpaperSlot home = null;
        WallpaperSlot lock = null;
        boolean recognized = false;
        for (String section : dump.split("(?m)(?=^mLast(?:Lock)?Wallpaper state:)")) {
            boolean isHome = section.startsWith(HOME_WALLPAPER_STATE);
            if (!isHome && !section.startsWith(LOCK_WALLPAPER_STATE)) continue;
            recognized = true;
            Matcher user = OWNER_WALLPAPER.matcher(section);
            if (!user.find()) continue;
            String details = user.group(2);
            boolean applied = WALLPAPER_NAME.matcher(details).find() && WALLPAPER_ENGINE.matcher(details).find();
            WallpaperSlot slot = new WallpaperSlot(Long.parseLong(user.group(1)), applied);
            if (isHome) home = slot;
            else lock = slot;
        }
        if (!recognized) throw new IOException("홈·잠금 배경 상태 조회 실패");
        return new WallpaperState(home, lock);
    }
    private WallpaperState queryWallpaperState() throws Exception {
        return wallpaperState(required(WALLPAPER_STATE_QUERY, 3).output);
    }
    private boolean awaitWallpaper(WallpaperState before) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        WallpaperState previous = null;
        do {
            try {
                WallpaperState current = queryWallpaperState();
                if (current.replaced(before) && current.equals(previous)) return true;
                previous = current;
            } catch (ConnectionLost e) {
                throw e;
            } catch (IOException e) {
                previous = null; // A transient dump failure cannot confirm a wallpaper.
            }
            if (System.nanoTime() >= deadline) return false;
            pause(250);
        } while (true);
    }
    void wallpaper(String model) throws Exception {
        String file = series(model) + ".png", path = "/sdcard/DCIM/ForHoliday/" + file;
        bridge.copy(file, path);
        commands("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d " + quote("file://" + path));
        for (int attempt = 0; attempt < 2; attempt++) {
            cleanLive();
            WallpaperState before;
            try { before = queryWallpaperState(); }
            catch (ConnectionLost e) { throw e; }
            catch (IOException e) { before = null; }
            Result result = dex("wallpaper_setter.dex", "WallpaperSetter", path);
            if (before != null && result.code == 0 && !result.truncated && result.output.contains("SUCCESS:WALLPAPER_SET:")
                    && !result.output.contains("FAIL:") && awaitWallpaper(before)) {
                notes.add("새 홈·잠금 배경 적용 확인");
                return;
            }
        }
        warnings.add("홈·잠금 배경 적용 완료를 확인하지 못했습니다.");
    }
    static boolean liveOverride(String dump) {
        return (dump.contains("InfinityWallpaper") || dump.contains("wallpaper.live")) && !dump.contains("mName=WallpaperSetter");
    }
}
