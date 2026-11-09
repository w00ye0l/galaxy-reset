package com.forholiday.galaxyreset;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Runs as a short-lived shell process. Uses Samsung's normal home-screen controls. */
public final class LanguageHomePlacement {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final List<HomeApp> APPS = List.of(
        new HomeApp("갤러리", Set.of("Gallery", "갤러리", "ギャラリー", "相册", "相簿", "图库", "媒體瀏覽器", "媒體瀏覽", "媒体浏览器")),
        new HomeApp("Play 스토어", Set.of("Play Store", "Play 스토어", "Play ストア", "Play 商店", "Google Play 商店")),
        new HomeApp("LOCARORA", Set.of("LOCARORA")),
        new HomeApp("Language", Set.of("Language")));
    private static final Set<String> ADD_LABELS = Set.of("Add to Home", "Add to Home screen", "ホームに追加",
        "ホーム画面に追加", "홈에 추가", "홈 화면에 추가", "添加到主屏幕", "添加至主屏幕", "新增至主畫面", "加入主畫面", "新增到主畫面");
    private static final Set<String> REMOVE_LABELS = Set.of("Remove", "Remove from Home", "Remove from Home screen",
        "삭제", "홈에서 삭제", "홈 화면에서 삭제", "홈에서 제거", "홈 화면에서 제거",
        "削除", "ホームから削除", "ホーム画面から削除", "移除", "从主屏幕移除", "從主畫面移除");
    private final UiAutomation ui;

    private record Page(AccessibilityNodeInfo grid, int index, int count) {}
    private record HomeApp(String name, Set<String> labels) {}
    private record Shortcut(String label, Rect bounds, boolean folder) {}
    private record Grid(Rect area, int columns, int rows) {
        Rect cell(int column, int row) {
            return new Rect(area.left + area.width() * column / columns, area.top + area.height() * row / rows,
                area.left + area.width() * (column + 1) / columns, area.top + area.height() * (row + 1) / rows);
        }
        Rect destination(int index) { return cell(columns - APPS.size() + index, rows - 1); }
    }

    private LanguageHomePlacement(UiAutomation ui) { this.ui = ui; }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        HandlerThread callbacks = new HandlerThread("language-home-ui");
        callbacks.start();
        UiAutomation automation = null;
        int status = 1;
        try {
            Class<?> connection = Class.forName("android.app.IUiAutomationConnection");
            Object binder = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = UiAutomation.class.getConstructor(Looper.class, connection).newInstance(callbacks.getLooper(), binder);
            UiAutomation.class.getMethod("connect", int.class)
                .invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            new LanguageHomePlacement(automation).placeHomeApps();
            System.out.println("SUCCESS:MAIN_HOME_LAYOUT");
            status = 0;
        } catch (IOException e) {
            System.out.println("FAIL:" + e.getMessage());
        } catch (Exception e) {
            android.util.Log.w("GalaxyResetHome", "Samsung launcher automation failed", e);
            System.out.println("FAIL:삼성 런처 화면을 읽을 수 없습니다. 홈 화면 배치를 확인해 주세요.");
        } finally {
            if (automation != null) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); }
                catch (Exception ignored) {}
            }
            callbacks.quitSafely();
        }
        System.exit(status);
    }

    private void placeHomeApps() throws IOException {
        Page main = mainPage();
        Grid layout = gridLayout(main.grid());
        removeOtherShortcuts(main);
        for (int i = 0; i < APPS.size(); i++) place(APPS.get(i), layout, layout.destination(i));

        AccessibilityNodeInfo grid = mainPage().grid();
        if (shortcuts(grid).size() != APPS.size()) throw new IOException("메인 홈에 다른 바로가기가 남아 있습니다.");
        for (int i = 0; i < APPS.size(); i++) {
            Rect icon = appIcon(grid, APPS.get(i));
            if (icon == null || !layout.destination(i).contains(icon.centerX(), icon.centerY()))
                throw new IOException(APPS.get(i).name() + "의 메인 홈 위치 확인 필요");
        }
    }

    private void place(HomeApp app, Grid layout, Rect destination) throws IOException {
        Page main = mainPage();
        Rect icon = appIcon(main.grid(), app);
        if (icon != null && destination.contains(icon.centerX(), icon.centerY())) return;
        for (Shortcut shortcut : shortcuts(main.grid())) {
            if (!destination.contains(shortcut.bounds().centerX(), shortcut.bounds().centerY())) continue;
            Rect spare = freeCell(main.grid(), layout);
            dragToMain(shortcut.bounds(), spare, 0, layout.area());
            main = awaitPage();
            break;
        }
        if (!empty(main.grid(), destination)) throw new IOException(app.name() + "의 목표 칸이 이미 사용 중입니다.");

        Page source = main;
        if (icon == null) {
            // A retry can find the icon on the page opened by Samsung's Add to Home action.
            // Keep the search bounded and stop on layouts with too many pages to verify.
            if (main.count() > 7) throw new IOException("홈 페이지가 너무 많아 자동 배치 위치를 확인할 수 없습니다.");
            for (int i = main.index() + 1; i < main.count(); i++) {
                Rect grid = bounds(source.grid());
                swipe(grid.right - grid.width() / 8, grid.centerY(), grid.left + grid.width() / 8, grid.centerY());
                source = awaitPage();
                if (source.index() != i) throw new IOException("홈 페이지 이동 확인 필요");
                icon = appIcon(source.grid(), app);
                if (icon != null) break;
            }
            if (icon == null) {
                main = mainPage();
                addFromDrawer(bounds(main.grid()), app);
                source = awaitPage();
                icon = appIcon(source.grid(), app);
                if (icon == null) throw new IOException("홈에 추가된 " + app.name() + " 아이콘을 찾을 수 없습니다.");
            }
        }
        int pagesLeft = source.index() - main.index();
        if (pagesLeft < 0 || pagesLeft > 5) throw new IOException("홈 페이지 이동 확인 필요");
        if (pagesLeft != 0 || !destination.contains(icon.centerX(), icon.centerY()))
            dragToMain(icon, destination, pagesLeft, bounds(source.grid()));
        Rect placed = appIcon(mainPage().grid(), app);
        if (placed == null || !destination.contains(placed.centerX(), placed.centerY()))
            throw new IOException(app.name() + " 아이콘의 메인 홈 배치를 확인해 주세요.");
    }

    private void addFromDrawer(Rect grid, HomeApp app) throws IOException {
        swipe(grid.centerX(), grid.bottom - grid.height() / 8, grid.centerX(), grid.top + grid.height() / 3);
        AccessibilityNodeInfo drawer = await("app_search_edit_text");
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (drawer == null || root == null) throw new IOException("삼성 앱 목록 열기 확인 필요");
        Rect icon = appIcon(root, app);
        if (icon == null) throw new IOException("삼성 앱 목록에서 " + app.name() + " 앱을 찾아 홈 화면에 추가해 주세요.");
        openShortcutMenu(icon);
        AccessibilityNodeInfo title = await("quickoption_title_text");
        if (title == null || !app.labels().contains(text(title))) throw new IOException(app.name() + " 앱 메뉴 확인 필요");
        tapMenuAction(ADD_LABELS, app.name() + " 홈에 추가 메뉴 확인 필요");
        SystemClock.sleep(900);
    }

    private void removeOtherShortcuts(Page main) throws IOException {
        for (int i = 0; i < 63; i++) {
            List<Shortcut> current = shortcuts(main.grid());
            Set<HomeApp> seen = new HashSet<>();
            Shortcut extra = null;
            for (Shortcut shortcut : current) {
                HomeApp app = APPS.stream().filter(a -> !shortcut.folder() && a.labels().contains(shortcut.label())).findFirst().orElse(null);
                if (app == null || !seen.add(app)) {
                    extra = shortcut;
                    break;
                }
            }
            if (extra == null) return;
            openShortcutMenu(extra.bounds());
            // One UI lets users rename folders directly from the menu.
            AccessibilityNodeInfo title = extra.folder()
                ? await("quickoption_title_edit_text", "quickoption_title_text")
                : await("quickoption_title_text");
            if (title == null || !extra.label().equals(text(title)))
                throw new IOException(extra.label() + " 홈 바로가기 메뉴 확인 필요");
            tapMenuAction(REMOVE_LABELS, extra.label() + " 홈에서 제거 메뉴 확인 필요");
            SystemClock.sleep(500);
            Page after = awaitPage();
            if (after.index() != main.index() || shortcuts(after.grid()).size() >= current.size())
                throw new IOException(extra.label() + " 홈 바로가기 제거 확인 필요");
            main = after;
        }
        throw new IOException("메인 홈에 정리할 바로가기가 너무 많습니다.");
    }

    private static void openShortcutMenu(Rect icon) throws IOException {
        input("swipe", "" + icon.centerX(), "" + icon.centerY(), "" + icon.centerX(), "" + icon.centerY(), "850");
    }

    private void tapMenuAction(Set<String> labels, String failure) throws IOException {
        AccessibilityNodeInfo menu = ui.getRootInActiveWindow();
        if (menu == null) throw new IOException(failure);
        Rect target = null;
        for (AccessibilityNodeInfo action : menu.findAccessibilityNodeInfosByViewId(id("global_option_label"))) {
            if (action.isVisibleToUser() && labels.contains(text(action))) {
                if (target != null) throw new IOException(failure);
                target = bounds(action);
            }
        }
        if (target == null || target.isEmpty()) throw new IOException(failure);
        input("tap", "" + target.centerX(), "" + target.centerY());
    }

    private Page mainPage() throws IOException {
        home(); SystemClock.sleep(550);
        home(); SystemClock.sleep(550);
        return awaitPage();
    }

    private void home() throws IOException {
        input("keyevent", "KEYCODE_HOME");
    }

    private Page awaitPage() throws IOException {
        AccessibilityNodeInfo grid = await("wsCellLayout");
        if (grid == null) throw new IOException("삼성 홈 화면을 열고 화면 잠금을 확인하세요.");
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        AccessibilityNodeInfo indicator = visible(root, "workspace_page_indicator");
        AccessibilityNodeInfo pages = indicator == null ? null : indicator.getChild(0);
        if (pages == null) return new Page(grid, 0, 1);
        for (int i = 0; i < pages.getChildCount(); i++) {
            AccessibilityNodeInfo page = pages.getChild(i);
            if (page != null && page.isSelected()) return new Page(grid, i, pages.getChildCount());
        }
        throw new IOException("현재 홈 페이지 확인 필요");
    }

    private AccessibilityNodeInfo await(String... names) {
        for (int i = 0; i < 15; i++) {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            for (String name : names) {
                AccessibilityNodeInfo found = visible(root, name);
                if (found != null) return found;
            }
            SystemClock.sleep(200);
        }
        return null;
    }

    private static AccessibilityNodeInfo visible(AccessibilityNodeInfo root, String name) {
        if (root == null) return null;
        CharSequence owner = root.getPackageName();
        if (owner == null || !LAUNCHER.contentEquals(owner)) return null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id(name)))
            if (node.isVisibleToUser()) return node;
        return null;
    }

    private static Rect appIcon(AccessibilityNodeInfo root, HomeApp app) throws IOException {
        Rect found = null;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id("icon"))) {
            if (!node.isVisibleToUser() || !app.labels().contains(text(node))) continue;
            if (found != null) throw new IOException(app.name() + " 아이콘이 여러 개 있습니다. 홈 화면에서 확인해 주세요.");
            found = bounds(node);
        }
        return found;
    }

    private static List<Shortcut> shortcuts(AccessibilityNodeInfo grid) {
        List<Shortcut> result = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) {
            AccessibilityNodeInfo child = grid.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            String resource = child.getViewIdResourceName();
            boolean folder = id("folder_icon_container").equals(resource);
            if (!folder && !id("icon_container").equals(resource)) continue;
            AccessibilityNodeInfo title = visible(child, folder ? "folder_icon_view" : "icon");
            result.add(new Shortcut(title == null ? "" : text(title), bounds(child), folder));
        }
        return result;
    }

    private static Grid gridLayout(AccessibilityNodeInfo grid) throws IOException {
        Rect area = bounds(grid);
        for (Shortcut shortcut : shortcuts(grid)) {
            Rect cell = shortcut.bounds();
            if (cell.isEmpty() || !area.contains(cell)) continue;
            int columns = Math.round((float) area.width() / cell.width());
            int rows = Math.round((float) area.height() / cell.height());
            if (columns < APPS.size() || columns > 7 || rows < 3 || rows > 9
                    || Math.abs(area.width() - columns * cell.width()) > columns * 3
                    || Math.abs(area.height() - rows * cell.height()) > rows * 3) continue;
            return new Grid(area, columns, rows);
        }
        throw new IOException("메인 홈의 앱 그리드를 확인할 수 없습니다.");
    }

    private static Rect freeCell(AccessibilityNodeInfo grid, Grid layout) throws IOException {
        for (int row = 0; row < layout.rows() - 1; row++) {
            for (int column = 0; column < layout.columns(); column++) {
                Rect cell = layout.cell(column, row);
                if (empty(grid, cell)) return cell;
            }
        }
        throw new IOException("메인 홈에 아이콘을 임시로 이동할 빈 칸이 없습니다.");
    }

    private static boolean empty(AccessibilityNodeInfo grid, Rect slot) {
        Rect interior = new Rect(slot);
        interior.inset(5, 5);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AccessibilityNodeInfo child = grid.getChild(i);
            if (child != null && child.isVisibleToUser() && Rect.intersects(interior, bounds(child)))
                return false;
        }
        return true;
    }

    private void swipe(int x, int y, int tx, int ty) throws IOException {
        input("swipe", "" + x, "" + y, "" + tx, "" + ty, "350");
        SystemClock.sleep(650);
    }

    private void dragToMain(Rect icon, Rect slot, int pagesLeft, Rect grid) throws IOException {
        int x = icon.centerX(), y = icon.centerY(), tx = slot.centerX(), ty = slot.centerY();
        touch("DOWN", x, y);
        try {
            SystemClock.sleep(850);
            touch("MOVE", x + (x < grid.centerX() ? 65 : -65), y + 16);
            SystemClock.sleep(220);
            for (int i = 0; i < pagesLeft; i++) {
                touch("MOVE", grid.left + 65, y);
                touch("MOVE", 8, y);
                SystemClock.sleep(1500);
            }
            touch("MOVE", tx, ty);
            SystemClock.sleep(420);
            touch("UP", tx, ty);
        } catch (IOException e) {
            try { touch("CANCEL", tx, ty); } catch (IOException ignored) {}
            throw e;
        }
        SystemClock.sleep(600);
    }

    private static void touch(String action, int x, int y) throws IOException {
        input("motionevent", action, Integer.toString(x), Integer.toString(y));
    }

    private static void input(String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("/system/bin/input"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("삼성 런처 입력 동작을 확인할 수 없습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("삼성 런처 입력이 중단됐습니다.", e);
        } finally { process.destroyForcibly(); }
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect result = new Rect();
        node.getBoundsInScreen(result);
        return result;
    }

    private static String id(String name) { return LAUNCHER + ":id/" + name; }
    private static String text(AccessibilityNodeInfo node) { return node.getText() == null ? "" : node.getText().toString(); }
}
