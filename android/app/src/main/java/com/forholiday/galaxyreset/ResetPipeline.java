package com.forholiday.galaxyreset;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Android-independent V6 port. Only Bridge performs privileged operations. */
final class ResetPipeline {
    static final String SELF = "com.forholiday.galaxyreset";
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    static final String TMP = "/data/local/tmp/galaxy-reset/";
    static final String LAUNCHER = "com.sec.android.app.launcher/.activities.LauncherActivity";
    static final String[] LABELS = {"언어 설정", "계정 삭제", "e-SIM 프로필 삭제", "Google 앱 기록 삭제", "사용자 설치 앱 삭제",
        "내부 저장소 초기화", "로그·캐시 정리", "갤러리 휴지통 정리", "미디어 스토어 갱신", "썸네일 잔여물 제거",
        "카메라 설정 초기화", "내비게이션 바 초기화", "글자 크기 초기화", "홈 독 앱 배치", "런처 초기화",
        "Galaxy Store 복원 차단", "배경화면 적용", "필수 앱 설치", "최근 앱 목록 제거"};
    static final Set<String> KEEP = Set.of(SELF, SHIZUKU, "com.sec.android.app.popupcalculator", "com.nhn.android.nmap", "com.alphainventor.filemanager", "com.sec.android.app.sbrowser");
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
        Result result = raw(command, 120);
        if (result.code != 0 || result.truncated || result.output.contains("SecurityException") || result.output.contains("Error:") ||
            (command.startsWith("dumpsys") && result.output.trim().isEmpty())) throw new IOException("조회 실패: " + command);
        return result;
    }
    boolean installed(String pkg) throws Exception {
        Result r = required("pm list packages --user 0 " + quote(pkg));
        return Arrays.asList(r.output.split("\\R")).contains("package:" + pkg);
    }
    long packageVersion(String pkg) throws Exception {
        Matcher version=Pattern.compile("\\bversionCode=(\\d+)\\b").matcher(required("dumpsys package "+quote(pkg)).output);
        if(!version.find())throw new IOException("설치 버전 조회 실패: "+pkg);
        return Long.parseLong(version.group(1));
    }
    void nmapInstallSuccess(Result result) throws IOException {
        if(result.code!=0||result.truncated||!result.output.contains("Success")||result.output.contains("Failure"))
            throw new IOException("네이버 지도 분할 APK 설치 실패: "+result.output.substring(0,Math.min(180,result.output.length())));
    }
    void prepareNmap() throws Exception {
        String pkg="com.nhn.android.nmap";
        if(!installed(pkg)||packageVersion(pkg)<BuildConfig.NMAP_VERSION_CODE){
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
        }
        if(!installed(pkg)||packageVersion(pkg)<BuildConfig.NMAP_VERSION_CODE)throw new IOException("네이버 지도 설치 버전 확인 실패");
        checked("pm enable --user 0 "+pkg,30);checked("am force-stop --user 0 "+pkg,30);
        try{
            String[] resolved=required("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "+pkg).output.trim().split("\\R");
            String component=resolved[resolved.length-1];
            if(!component.matches("com\\.nhn\\.android\\.nmap/[A-Za-z0-9_.$]+"))throw new IOException("네이버 지도 실행 화면 조회 실패");
            Result start=required("am start -W --user 0 -n "+quote(component));
            if(!start.output.contains("Status: ok"))throw new IOException("네이버 지도 실행 실패");
            pause(2000);
            if(!required("pidof "+pkg).output.trim().matches("[0-9\\s]+"))throw new IOException("네이버 지도 실행 직후 종료됨");
            notes.add("네이버 지도 버전·시작 확인 완료 · 첫 안내/권한 및 지도 로딩은 별도 확인");
        }finally{try{raw("am force-stop --user 0 "+pkg,30);}catch(Exception ignored){}}
    }
    void commands(String... commands) throws Exception { for (String c : commands) checked(c, 120); }
    void clear(String... packages) throws Exception {
        for (String pkg : packages) if (installed(pkg)) checked("pm clear --user 0 " + quote(pkg), 120);
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
        int count = 0;
        for (String line : dump.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Account {")) continue;
            int typeStart = trimmed.lastIndexOf("type="), close = trimmed.lastIndexOf('}');
            if (typeStart < 0 || close <= typeStart + 5) continue;
            String type = trimmed.substring(typeStart + 5, close).trim();
            if (!type.startsWith("com.samsung") && !type.startsWith("com.osp.app.signin")) count++;
        }
        return count;
    }
    static final String WIDGET_QUERY = "dump=$(dumpsys appwidget) || exit $?; printf '%s\\n' \"$dump\" | sed -n '/^[[:space:]]*Widgets:/,/^[[:space:]]*Hosts:/p'";
    Set<String> queryWidgetProviders() throws Exception {
        Result result = required(WIDGET_QUERY);
        if (!result.output.contains("Widgets:")) throw new IOException("위젯 영역 조회 실패");
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
    void restartLauncher() throws Exception {
        commands("am force-stop com.sec.android.app.launcher"); pause(1000);
        commands("am start --user 0 -n " + LAUNCHER);
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
    void step(int index, String locale, String model) throws Exception {
        warnings.clear(); notes.clear(); manual = false;
        switch (index) {
            case 0 -> success(dex("locale_changer.dex", "LocaleChanger", locales(locale).toArray(new String[0])), "SUCCESS", "언어 변경 확인 필요");
            case 1 -> {
                required("dumpsys account");
                Result result = dex("account_remover.dex", "AccountRemover");
                int remaining = nonSamsungAccounts(required("dumpsys account").output);
                if (remaining > 0 || result.output.contains("FAIL:")) {
                    manual = true; warnings.add("비삼성 계정 삭제 실패 · 남은 계정 " + remaining + "개, 설정에서 수동 확인 필요");
                }
            }
            case 2 -> {
                Result result = dex("esim_manager.dex", "EsimManager", "delete-all");
                boolean done = result.output.contains("SUCCESS:NO_ESIM") || result.output.contains("SUCCESS:ALL_ESIM_DELETED");
                if (!done || result.output.contains("FAIL:ESIM_DELETE") || result.output.contains("FAIL:ALL_ESIM_DELETE_FAILED")) {
                    manual = true; warnings.add("e-SIM 수동 삭제 필요 · SIM 관리자에서 확인하세요.");
                    commands("am start -a android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS");
                }
            }
            case 3 -> clear("com.google.android.googlequicksearchbox", "com.android.chrome", "com.google.android.youtube", "com.google.android.gm",
                "com.google.android.apps.maps", "com.google.android.apps.docs", "com.google.android.calendar", "com.google.android.apps.photos", "com.sec.android.app.sbrowser");
            case 4 -> {
                Result r = required("pm list packages --user 0 -3");
                for (String line : r.output.split("\\R")) {
                    if (!line.startsWith("package:")) continue;
                    String pkg = line.substring(8).trim();
                    if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IOException("잘못된 패키지 목록");
                    if (!KEEP.contains(pkg)) success(checked("pm uninstall --user 0 " + quote(pkg), 120), "Success", "앱 삭제 실패: " + pkg);
                }
            }
            case 5 -> {
                checked("rm -rf /storage/emulated/0/*", 300);
                checked("rm -rf /storage/emulated/0/.[!.]* /storage/emulated/0/..?*", 300);
                // Scoped-storage restrictions can leave entries even when rm's reporting varies.
                Result r = required("find /storage/emulated/0 -type f");
                int remaining = remainingUserFiles(r.output);
                if (remaining > 0) { manual = true; warnings.add("내부 저장소 잔여 파일 " + remaining + "개 · 수동 확인 필요"); }
            }
            case 6 -> {
                Result result;
                try { result = dex("content_cleaner.dex", "ContentCleaner"); }
                catch (ConnectionLost e) { throw e; }
                catch (Exception e) { result = new Result(1, "PERMISSION_DENIED:CONTACTS\nPERMISSION_DENIED:SMS"); }
                String output = result.output;
                if (output.contains("PERMISSION_DENIED:CALL_LOG") || output.contains("PERMISSION_DENIED:CONTACTS") ||
                    output.contains("FALLBACK:CALL_LOG") || output.contains("FALLBACK:CONTACTS")) clear("com.android.providers.contacts");
                if (output.contains("PERMISSION_DENIED:SMS") || output.contains("FALLBACK:SMS")) clear("com.android.providers.telephony");
                if (output.contains("FAIL:") || (!output.contains("SUCCESS:") && !output.contains("PARTIAL:") && !output.contains("PERMISSION_DENIED:") && !output.contains("FALLBACK:"))) warnings.add("통화/SMS/주소록 삭제 결과 확인 필요");
                clear("com.samsung.android.messaging", "com.samsung.android.providers.contacts", "com.samsung.android.app.contacts",
                    "com.samsung.android.dsms", "com.android.mms.service", "com.sec.android.gallery3d", "com.nhn.android.nmap", "com.sec.android.themestore",
                    "com.sec.android.app.vepreload", "com.samsung.android.app.clipboardedge", "com.samsung.android.honeyboard");
            }
            case 7 -> {
                commands("am force-stop com.sec.android.gallery3d", "am force-stop com.sec.android.app.myfiles");
                List<String> paths = new ArrayList<>(List.of(
                    "/sdcard/Android/data/com.sec.android.gallery3d/files/Trash", "/sdcard/Android/data/com.sec.android.gallery3d/files/.Trash",
                    "/sdcard/Android/data/com.sec.android.gallery3d/cache", "/sdcard/Android/data/com.sec.android.app.myfiles/files/Trash",
                    "/sdcard/Android/data/com.sec.android.app.myfiles/files/.Trash", "/sdcard/Android/data/com.sec.android.app.vepreload/files/Trash",
                    "/storage/emulated/0/Android/.Trash", "/sdcard/.Trash", "/sdcard/.Trash-0", "/sdcard/Recycle",
                    "/sdcard/Android/data/com.sec.android.gallery3d/files/.thumbnails", "/sdcard/Android/data/com.android.chrome/cache",
                    "/sdcard/Android/data/dji.mimo/cache", "/sdcard/Android/data/com.nhn.android.nmap/cache"));
                for (String folder : FOLDERS) paths.add("/sdcard/" + folder + "/.thumbnails");
                for (String path : paths) checked("rm -rf " + quote(path), 120);
                clear("com.sec.android.gallery3d", "com.sec.android.app.myfiles", "com.samsung.android.providers.media",
                    "com.samsung.android.providers.trash", "com.android.providers.media", "com.google.android.providers.media.module");
                Result r = raw("am broadcast -a android.intent.action.MEDIA_MOUNTED -d file:///sdcard", 120);
                if (r.code != 0 || r.output.contains("SecurityException"))
                    commands("am force-stop com.android.providers.media", "am force-stop com.google.android.providers.media.module");
                pause(2000);
            }
            case 8 -> {
                for (String uri : List.of("images/media", "video/media", "audio/media", "file"))
                    checked("content delete --user 0 --uri content://media/external/" + uri, 120);
            }
            case 9 -> { for (String folder : FOLDERS) checked("rm -rf /sdcard/" + folder + "/.thumbnails", 120); }
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
            case 13 -> {
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
            case 14 -> {
                clear("com.sec.android.app.launcher"); pause(1000);
                commands("am start --user 0 -n " + LAUNCHER); pause(4000);
                Set<String> providers = queryWidgetProviders();
                try {
                    disableTemporarily(providers);
                    if (!providers.isEmpty()) { restartLauncher(); pause(4000); commands("input keyevent KEYCODE_HOME"); pause(8000); }
                } finally { restorePending(); }
            }
            case 15 -> {
                List<String> packages = new ArrayList<>();
                for (String pkg : List.of("com.sec.android.app.samsungapps", "com.samsung.android.scloud")) if (installed(pkg)) packages.add(pkg);
                clear(packages.toArray(new String[0]));
                try { disableTemporarily(packages); restartLauncher(); pause(5000); }
                finally { restorePending(); }
            }
            case 16 -> wallpaper(model);
            case 17 -> {
                prepareNmap();
                for (String pkg : List.of("com.alphainventor.filemanager", SELF)) {
                    if (installed(pkg)) continue;
                    if (pkg.equals(SELF)) { warnings.add("초기화 앱 설치 상태 확인 필요"); continue; }
                    String asset = "filemanager.apk";
                    bridge.copy(asset, TMP + asset);
                    success(checked("pm install -r --user 0 " + quote(TMP + asset), 300), "Success", "필수 앱 설치 실패: " + pkg);
                    if (!installed(pkg)) warnings.add("필수 앱 없음: " + pkg);
                    checked("rm -f " + quote(TMP + asset), 30);
                }
                commands("pm grant --user 0 " + SELF + " android.permission.WRITE_SECURE_SETTINGS");
            }
            case 18 -> success(dex("recent_tasks_cleaner.dex", "RecentTasksCleaner"), "SUCCESS", "최근 앱 삭제 확인 필요");
            default -> throw new IllegalArgumentException("잘못된 단계");
        }
    }
    void settings(String... entries) throws Exception { for (String entry : entries) checked("settings --user 0 put " + entry, 120); }
    void cleanLive() throws Exception {
        for (String pkg : LIVE) commands("am force-stop " + pkg);
        clear(LIVE); pause(3000);
    }
    void wallpaper(String model) throws Exception {
        String file = series(model) + ".png", path = "/sdcard/DCIM/ForHoliday/" + file;
        bridge.copy(file, path);
        commands("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d " + quote("file://" + path));
        cleanLive();
        Result result = dex("wallpaper_setter.dex", "WallpaperSetter", path);
        if (result.output.contains("FAIL")) { cleanLive(); result = dex("wallpaper_setter.dex", "WallpaperSetter", path); }
        success(result, "SUCCESS", "홈/잠금 배경화면 설정 실패");
        pause(5000);
        String dump = required("dumpsys wallpaper").output;
        if (liveOverride(dump)) {
            cleanLive(); success(dex("wallpaper_setter.dex", "WallpaperSetter", path), "SUCCESS", "배경화면 재설정 실패");
            pause(5000);
            if (liveOverride(required("dumpsys wallpaper").output)) warnings.add("라이브 배경이 다시 적용됨 · 수동 확인 필요");
        }
    }
    static boolean liveOverride(String dump) {
        return (dump.contains("InfinityWallpaper") || dump.contains("wallpaper.live")) && !dump.contains("mName=WallpaperSetter");
    }
}
