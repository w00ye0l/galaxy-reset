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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
    private static final Set<String> SELECT_LABELS = Set.of("Select", "선택", "選択", "选择", "選擇");
    private static final Pattern PAGE_NUMBERS = Pattern.compile("\\p{Nd}+[^\\p{Nd}]+\\p{Nd}+");
    private static final Pattern PAGE_MESSAGE = Pattern.compile("page|페이지|ページ|页面|頁面|主屏幕|主畫面", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DELETE_PAGE_LABELS = Set.of("Delete", "Remove", "삭제", "제거", "削除", "删除", "刪除", "移除");
    private final UiAutomation ui;
    private final Set<HomeApp> absentFromHomePages = new HashSet<>();
    private final Map<Integer, Set<HomeApp>> drawerPageApps = new HashMap<>();

    private record Page(AccessibilityNodeInfo grid, int index, int count) {}
    private record HomeIcon(Page page, Rect bounds) {}
    private record HomeApp(String name, Set<String> labels) {}
    private record Shortcut(String label, Rect bounds, boolean folder, String selection) {}
    private record EditPages(List<AccessibilityNodeInfo> dots, int selected) {}
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
            LanguageHomePlacement placement = new LanguageHomePlacement(automation);
            if (args.length == 1 && args[0].equals("--clear-search-bar")) {
                placement.clearSearchBar();
                System.out.println("SUCCESS:HOME_SEARCH_CHECKED");
            } else {
                placement.placeHomeApps();
                System.out.println("SUCCESS:MAIN_HOME_LAYOUT");
            }
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
        main = tryRemoveShortcutsTogether(main);
        main = removeOtherShortcuts(main);
        main = tryAddDrawerAppsTogether(main, layout);
        for (int i = 0; i < APPS.size(); i++) {
            main = place(main, APPS.get(i), layout, layout.destination(i));
        }
        AccessibilityNodeInfo grid = keepOnlyMainPage(main, layout).grid();
        if (shortcuts(grid).size() != APPS.size()) throw new IOException("메인 홈에 다른 바로가기가 남아 있습니다.");
        for (int i = 0; i < APPS.size(); i++) {
            Rect icon = appIcon(grid, APPS.get(i));
            if (icon == null || !layout.destination(i).contains(icon.centerX(), icon.centerY()))
                throw new IOException(APPS.get(i).name() + "의 메인 홈 위치 확인 필요");
        }
    }

    private void clearSearchBar() throws IOException {
        Page main = mainPage();
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (visible(root, "qsb_container") == null) return;
        openShortcutMenu(freeCell(main.grid(), gridLayout(main.grid())));
        AccessibilityNodeInfo settings = await("home_setting_button");
        if (settings == null) throw new IOException("삼성 홈 설정 열기 확인 필요");
        tap(bounds(settings));
        try {
            for (int scroll = 0; scroll < 5; scroll++) {
                AccessibilityNodeInfo list = await("recycler_view");
                if (list == null) break;
                for (AccessibilityNodeInfo title : list.findAccessibilityNodeInfosByViewId("android:id/title")) {
                    if (!title.isVisibleToUser() || !Set.of("Show search on Home screen", "홈 화면에 검색 표시",
                            "ホーム画面に検索を表示", "在主屏幕上显示搜索", "在主螢幕上顯示搜尋").contains(text(title))) continue;
                    tap(bounds(title));
                    turnOffSearchSwitch("search_google_switch");
                    turnOffSearchSwitch("search_finder_switch");
                    mainPage();
                    if (visible(ui.getRootInActiveWindow(), "qsb_container") != null)
                        throw new IOException("홈 검색 바 제거 확인 필요");
                    return;
                }
                Rect area = bounds(list);
                swipe(area.centerX(), area.bottom - area.height() / 5,
                    area.centerX(), area.top + area.height() / 4);
            }
            throw new IOException("홈 화면 검색 표시 설정을 찾을 수 없습니다.");
        } catch (IOException e) {
            home();
            throw e;
        }
    }

    private void turnOffSearchSwitch(String name) throws IOException {
        AccessibilityNodeInfo toggle = await(name);
        if (toggle == null || !toggle.isCheckable()) throw new IOException("홈 검색 바 스위치 확인 필요");
        if (!toggle.isChecked()) return;
        tap(bounds(toggle));
        SystemClock.sleep(350);
        toggle = await(name);
        if (toggle == null || toggle.isChecked()) throw new IOException("홈 검색 바 끄기 확인 필요");
    }

    private Page keepOnlyMainPage(Page main, Grid layout) throws IOException {
        if (main.count() == 1) return awaitPage();

        openShortcutMenu(freeCell(main.grid(), layout));
        if (await("page_edit_panel") == null) throw new IOException("삼성 홈 페이지 편집 화면 확인 필요");
        EditPages pages = editPages();
        int keep = pages.selected();
        if (keep < 0 || pages.dots().size() > 7) throw new IOException("보존할 메인 홈 페이지 확인 필요");

        while (pages.dots().size() > 1) {
            int before = pages.dots().size();
            // Remove from the end first so the main page's index normally stays the same.
            int extra = before - 1 == keep ? 0 : before - 1;
            tap(bounds(pages.dots().get(extra)));
            SystemClock.sleep(700);
            EditPages current = editPages();
            if (current.dots().size() != before || current.selected() != extra)
                throw new IOException("제거할 홈 페이지 선택 확인 필요");
            AccessibilityNodeInfo delete = await("delete_page_layout");
            if (delete == null || !delete.isClickable()) throw new IOException("홈 페이지 제거 버튼 확인 필요");
            tap(bounds(delete));
            SystemClock.sleep(600);
            confirmPageDeletion();
            pages = editPages();
            if (pages.dots().size() != before - 1) throw new IOException("홈 페이지 제거 결과 확인 필요");
            if (extra < keep) keep--;
        }
        home();
        SystemClock.sleep(550);
        return mainPage();
    }

    private EditPages editPages() throws IOException {
        AccessibilityNodeInfo indicator = await("workspace_page_indicator");
        AccessibilityNodeInfo group = indicator == null ? null : indicator.getChild(0);
        if (group == null) throw new IOException("편집 화면의 홈 페이지 수 확인 필요");
        List<AccessibilityNodeInfo> dots = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < group.getChildCount(); i++) {
            AccessibilityNodeInfo dot = group.getChild(i);
            CharSequence label = dot == null ? null : dot.getContentDescription();
            // Discover and Add Page are also in the indicator, but have no page number pair.
            if (label == null || !PAGE_NUMBERS.matcher(label).find()) continue;
            if (dot.isSelected()) selected = dots.size();
            dots.add(dot);
        }
        if (dots.isEmpty()) throw new IOException("편집 화면의 홈 페이지 수 확인 필요");
        return new EditPages(dots, selected);
    }

    private void confirmPageDeletion() throws IOException {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        AccessibilityNodeInfo confirm = visible(root, "android:id/button1");
        if (confirm == null) return;
        AccessibilityNodeInfo message = visible(root, "android:id/message");
        if (message == null || !PAGE_MESSAGE.matcher(text(message)).find() || !DELETE_PAGE_LABELS.contains(text(confirm)))
            throw new IOException("삼성 홈 페이지 제거 확인창 확인 필요");
        tap(bounds(confirm));
        SystemClock.sleep(600);
    }

    private static void tap(Rect target) throws IOException {
        if (target.isEmpty()) throw new IOException("삼성 런처 버튼 위치 확인 필요");
        input("tap", "" + target.centerX(), "" + target.centerY());
    }

    private Page place(Page main, HomeApp app, Grid layout, Rect destination) throws IOException {
        Rect icon = appIcon(main.grid(), app);
        if (inCell(icon, destination)) return main;
        for (Shortcut shortcut : shortcuts(main.grid())) {
            if (!destination.contains(shortcut.bounds().centerX(), shortcut.bounds().centerY())) continue;
            Rect spare = freeCell(main.grid(), layout);
            dragToMain(shortcut.bounds(), spare, 0, layout.area());
            main = awaitPage();
            break;
        }
        if (!empty(main.grid(), destination)) throw new IOException(app.name() + "의 목표 칸이 이미 사용 중입니다.");

        boolean searchOtherPages = !absentFromHomePages.contains(app) && main.index() + 1 < main.count();
        HomeIcon source = findHomeIcon(main, app);
        if (source == null) {
            if (searchOtherPages) main = mainPage();
            addFromDrawer(bounds(main.grid()), app);
            absentFromHomePages.remove(app);
            // One UI may add to another page while keeping the currently visible page.
            Page current = currentMainPage(main);
            main = current == null ? mainPage() : current;
            source = findHomeIcon(main, app);
            if (source == null && current != null) {
                // The launcher can expose the workspace before it finishes adding the shortcut.
                absentFromHomePages.remove(app);
                main = mainPage();
                source = findHomeIcon(main, app);
            }
            if (source == null) throw new IOException("홈에 추가된 " + app.name() + " 아이콘을 찾을 수 없습니다.");
        }
        icon = source.bounds();
        int pagesLeft = source.page().index() - main.index();
        if (pagesLeft < 0 || pagesLeft > 5) throw new IOException("홈 페이지 이동 확인 필요");
        if (pagesLeft == 0 && inCell(icon, destination)) return main;

        dragToMain(icon, destination, pagesLeft, bounds(source.page().grid()));
        if (pagesLeft == 0) {
            Page current = currentMainPage(main);
            if (current != null && inCell(appIcon(current.grid(), app), destination)) return current;
        }
        Page placedPage = mainPage();
        if (!inCell(appIcon(placedPage.grid(), app), destination))
            throw new IOException(app.name() + " 아이콘의 메인 홈 배치를 확인해 주세요.");
        return placedPage;
    }

    private Page currentMainPage(Page main) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        AccessibilityNodeInfo grid = visible(root, "wsCellLayout");
        if (grid == null) return null;
        try {
            Page current = readPage(root, grid);
            return current.index() == main.index() && current.count() == main.count() ? current : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean inCell(Rect icon, Rect cell) {
        return icon != null && cell.contains(icon.centerX(), icon.centerY());
    }

    private HomeIcon findHomeIcon(Page main, HomeApp app) throws IOException {
        Rect icon = appIcon(main.grid(), app);
        if (icon != null) return new HomeIcon(main, icon);
        if (absentFromHomePages.contains(app)) return null;
        if (main.count() > 7) throw new IOException("홈 페이지가 너무 많아 자동 배치 위치를 확인할 수 없습니다.");
        Set<HomeApp> found = visibleApps(main.grid());
        Page page = main;
        for (int index = main.index() + 1; index < main.count(); index++) {
            Rect area = bounds(page.grid());
            swipe(area.right - area.width() / 8, area.centerY(), area.left + area.width() / 8, area.centerY());
            page = awaitPage();
            if (page.index() != index) throw new IOException("홈 페이지 이동 확인 필요");
            icon = appIcon(page.grid(), app);
            if (icon != null) return new HomeIcon(page, icon);
            found.addAll(visibleApps(page.grid()));
        }
        for (HomeApp candidate : APPS) {
            if (!found.contains(candidate)) absentFromHomePages.add(candidate);
        }
        return null;
    }

    private void addFromDrawer(Rect grid, HomeApp app) throws IOException {
        swipe(grid.centerX(), grid.bottom - grid.height() / 8, grid.centerX(), grid.top + grid.height() / 3);
        AccessibilityNodeInfo drawer = await("app_search_edit_text");
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (drawer == null || root == null) throw new IOException("삼성 앱 목록 열기 확인 필요");
        Rect icon = drawerIcon(root, app);
        if (icon == null) throw new IOException("삼성 앱 목록에서 " + app.name() + " 앱을 찾아 홈 화면에 추가해 주세요.");
        openShortcutMenu(icon);
        AccessibilityNodeInfo title = await("quickoption_title_text");
        if (title == null || !app.labels().contains(text(title))) throw new IOException(app.name() + " 앱 메뉴 확인 필요");
        tapMenuAction(ADD_LABELS, app.name() + " 홈에 추가 메뉴 확인 필요");
        SystemClock.sleep(900);
    }

    private Page tryAddDrawerAppsTogether(Page main, Grid layout) throws IOException {
        Set<HomeApp> found = visibleApps(main.grid());
        if (APPS.size() - found.size() < 2) return main;
        if (main.count() > 7) return main;
        Page page = main;
        for (int index = main.index() + 1; index < main.count(); index++) {
            Rect area = bounds(page.grid());
            swipe(area.right - area.width() / 8, area.centerY(), area.left + area.width() / 8, area.centerY());
            page = awaitPage();
            if (page.index() != index) throw new IOException("홈 페이지 이동 확인 필요");
            found.addAll(visibleApps(page.grid()));
        }
        if (page.index() != main.index()) main = mainPage();
        List<HomeApp> missing = new ArrayList<>(APPS);
        missing.removeAll(found);
        absentFromHomePages.addAll(missing);
        if (missing.size() < 2) return main;

        Rect grid = bounds(main.grid());
        try {
            swipe(grid.centerX(), grid.bottom - grid.height() / 8, grid.centerX(), grid.top + grid.height() / 3);
            if (await("app_search_edit_text") == null) return mainPage();
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            List<HomeApp> together = new ArrayList<>();
            for (HomeApp app : missing) if (root != null && appIcon(root, app) != null) together.add(app);
            if (together.size() < 2) return mainPage();

            Rect first = appIcon(root, together.get(0));
            openShortcutMenu(first);
            AccessibilityNodeInfo title = await("quickoption_title_text");
            Rect select = menuAction(SELECT_LABELS);
            if (title == null || !together.get(0).labels().contains(text(title)) || select == null) return mainPage();
            tap(select);
            if (await("multi_select_panel") == null) return mainPage();
            root = ui.getRootInActiveWindow();
            String selected = drawerSelection(root, together.get(0));
            String unselected = drawerSelection(root, together.get(1));
            if (selected.isEmpty() || unselected.isEmpty() || selected.equals(unselected)
                    || !drawerSelectionMatches(root, together.subList(0, 1), selected, unselected)) return mainPage();
            for (HomeApp app : together.subList(1, together.size())) {
                Rect icon = appIcon(root, app);
                if (icon == null) return mainPage();
                tap(icon);
            }
            root = ui.getRootInActiveWindow();
            first = appIcon(root, together.get(0));
            if (first == null || !drawerSelectionMatches(root, together, selected, unselected)) return mainPage();
            Rect drop = groupDropCell(main.grid(), layout, together);
            absentFromHomePages.removeAll(together);
            dragDrawerGroup(first, drop);
        } catch (IOException e) {
            android.util.Log.i("GalaxyResetHome", "Using individual app placement: " + e.getMessage());
        }
        return mainPage();
    }

    private static Rect groupDropCell(AccessibilityNodeInfo grid, Grid layout, List<HomeApp> apps)
            throws IOException {
        // Let One UI fill the available bottom-row cells from the first requested position.
        // The normal placement loop still checks each app and moves any that land elsewhere.
        for (HomeApp app : apps) {
            if (!empty(grid, layout.destination(APPS.indexOf(app)))) return freeCell(grid, layout);
        }
        return layout.destination(APPS.indexOf(apps.get(0)));
    }

    private static String drawerSelection(AccessibilityNodeInfo root, HomeApp app) {
        if (root == null) return "";
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id("icon_container"))) {
            AccessibilityNodeInfo icon = visible(node, "icon");
            if (icon != null && app.labels().contains(text(icon))) return selectionSuffix(node);
        }
        return "";
    }

    private static String selectionSuffix(AccessibilityNodeInfo node) {
        CharSequence description = node.getContentDescription();
        if (description == null) return "";
        String value = description.toString();
        int separator = Math.max(value.lastIndexOf(','), Math.max(value.lastIndexOf('、'), value.lastIndexOf('，')));
        return separator < 0 ? "" : value.substring(separator + 1).strip();
    }

    private static boolean drawerSelectionMatches(AccessibilityNodeInfo root, List<HomeApp> targets,
                                                   String selected, String unselected) {
        if (visible(root, "multi_select_panel") == null) return false;
        int selectedCount = 0;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id("icon_container"))) {
            if (!node.isVisibleToUser()) continue;
            AccessibilityNodeInfo icon = visible(node, "icon");
            boolean wanted = icon != null && targets.stream().anyMatch(app -> app.labels().contains(text(icon)));
            if (!selectionSuffix(node).equals(wanted ? selected : unselected)) return false;
            if (wanted) selectedCount++;
        }
        for (AccessibilityNodeInfo folder : root.findAccessibilityNodeInfosByViewId(id("folder_icon_container"))) {
            if (folder.isVisibleToUser() && !selectionSuffix(folder).equals(unselected)) return false;
        }
        return selectedCount == targets.size();
    }

    private void dragDrawerGroup(Rect icon, Rect drop) throws IOException {
        int x = icon.centerX(), y = icon.centerY();
        boolean released = false;
        touch("DOWN", x, y);
        try {
            SystemClock.sleep(850);
            touch("MOVE", x + (x < drop.centerX() ? 65 : -65), y + 16);
            AccessibilityNodeInfo guide = await("drag_guide_drop");
            if (guide == null) return;
            Rect target = bounds(guide);
            if (target.isEmpty()) return;
            touch("MOVE", target.centerX(), target.centerY());
            SystemClock.sleep(1200);
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            AccessibilityNodeInfo workspace = visible(root, "wsCellLayout");
            if (workspace == null || visible(root, "app_search_edit_text") != null
                    || !bounds(workspace).contains(drop) || !empty(workspace, drop)) return;
            // Drag mode adds a temporary page and can hide Discover, changing the page count.
            // Verify the drop on a real workspace, then return to the default home page to place the icons.
            touch("MOVE", drop.centerX(), drop.centerY());
            SystemClock.sleep(600);
            touch("UP", drop.centerX(), drop.centerY());
            released = true;
            SystemClock.sleep(900);
        } finally {
            if (!released) touch("CANCEL", x, y);
        }
    }

    private Rect drawerIcon(AccessibilityNodeInfo root, HomeApp app) throws IOException {
        Rect icon = appIcon(root, app);
        AccessibilityNodeInfo pages = drawerPages(root);
        if (pages == null) return icon;
        int count = pages.getChildCount();
        if (count > 12) throw new IOException("삼성 앱 목록의 페이지가 너무 많아 자동으로 확인할 수 없습니다.");
        rememberDrawerPage(root, pages);
        if (icon != null) return icon;

        // Search known matches first, then unseen pages, then revisit cached pages if needed.
        Set<Integer> checked = new HashSet<>();
        for (int pass = 0; pass < 3; pass++) {
            for (int index = 0; index < count; index++) {
                Set<HomeApp> known = drawerPageApps.get(index);
                int priority = known == null ? 1 : known.contains(app) ? 0 : 2;
                if (priority != pass) continue;
                AccessibilityNodeInfo dot = pages.getChild(index);
                if (dot == null || !dot.isVisibleToUser() || !dot.isClickable() || !checked.add(index) || dot.isSelected()) continue;
                tap(bounds(dot));
                SystemClock.sleep(650);
                root = ui.getRootInActiveWindow();
                pages = drawerPages(root);
                AccessibilityNodeInfo current = pages == null || index >= pages.getChildCount() ? null : pages.getChild(index);
                if (current == null || !current.isSelected()) throw new IOException("삼성 앱 목록 페이지 이동 확인 필요");
                rememberDrawerPage(root, pages);
                icon = appIcon(root, app);
                if (icon != null) return icon;
            }
        }
        return null;
    }

    private void rememberDrawerPage(AccessibilityNodeInfo root, AccessibilityNodeInfo pages) {
        for (int index = 0; index < pages.getChildCount(); index++) {
            AccessibilityNodeInfo dot = pages.getChild(index);
            if (dot != null && dot.isSelected()) {
                drawerPageApps.put(index, visibleApps(root));
                return;
            }
        }
    }

    private static Set<HomeApp> visibleApps(AccessibilityNodeInfo root) {
        Set<HomeApp> found = new HashSet<>();
        for (AccessibilityNodeInfo icon : root.findAccessibilityNodeInfosByViewId(id("icon"))) {
            if (!icon.isVisibleToUser()) continue;
            String label = text(icon);
            for (HomeApp app : APPS) if (app.labels().contains(label)) found.add(app);
        }
        return found;
    }

    private static AccessibilityNodeInfo drawerPages(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo indicator = visible(root, "applist_page_indicator");
        return indicator == null ? null : indicator.getChild(0);
    }

    private Page tryRemoveShortcutsTogether(Page main) throws IOException {
        List<Shortcut> before = shortcuts(main.grid());
        Set<String> labels = new HashSet<>();
        List<Shortcut> extras = new ArrayList<>();
        Set<HomeApp> seen = new HashSet<>();
        for (Shortcut shortcut : before) {
            if (shortcut.label().isEmpty() || !labels.add(shortcut.label())) return main;
            if (isExtra(shortcut, seen)) extras.add(shortcut);
        }
        if (extras.size() < 2) return main;

        Shortcut first = extras.get(0);
        openShortcutMenu(first.bounds());
        AccessibilityNodeInfo title = await("quickoption_title_edit_text", "quickoption_title_text");
        Rect select = menuAction(SELECT_LABELS);
        if (title == null || !first.label().equals(text(title)) || select == null)
            return leaveShortcutSelection();
        tap(select);
        if (await("multi_select_panel") == null) return leaveShortcutSelection();

        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        Map<String, Shortcut> selecting = shortcutsByLabel(visible(root, "wsCellLayout"));
        if (!selecting.keySet().equals(labels)) return leaveShortcutSelection();
        String selected = selecting.get(first.label()).selection();
        String unselected = selecting.get(extras.get(1).label()).selection();
        if (selected.isEmpty() || unselected.isEmpty() || selected.equals(unselected)
                || !selectionMatches(root, labels, Set.of(first.label()), selected, unselected))
            return leaveShortcutSelection();

        Set<String> remove = new HashSet<>();
        remove.add(first.label());
        for (Shortcut extra : extras.subList(1, extras.size())) {
            tap(selecting.get(extra.label()).bounds());
            remove.add(extra.label());
        }
        root = ui.getRootInActiveWindow();
        if (!selectionMatches(root, labels, remove, selected, unselected)) return leaveShortcutSelection();
        AccessibilityNodeInfo button = visible(root, "multi_select_remove_shortcut_button");
        AccessibilityNodeInfo label = visible(root, "multi_select_remove_shortcut");
        if (button == null || label == null || !button.isEnabled() || !button.isClickable()
                || !REMOVE_LABELS.contains(text(label))) return leaveShortcutSelection();
        tap(bounds(button));
        SystemClock.sleep(500);
        Page after = awaitPage();
        labels.removeAll(remove);
        if (after.index() != main.index() || !shortcutsByLabel(after.grid()).keySet().equals(labels)
                || shortcuts(after.grid()).size() != labels.size())
            throw new IOException("홈 바로가기 일괄 제거 결과 확인 필요");
        android.util.Log.i("GalaxyResetHome", "Removed " + remove.size() + " shortcuts together");
        return after;
    }

    private Page leaveShortcutSelection() throws IOException {
        if (visible(ui.getRootInActiveWindow(), "multi_select_panel") != null) {
            input("keyevent", "KEYCODE_BACK");
            SystemClock.sleep(350);
        }
        return mainPage();
    }

    private static boolean selectionMatches(AccessibilityNodeInfo root, Set<String> labels, Set<String> selectedLabels,
                                            String selected, String unselected) {
        Map<String, Shortcut> workspace = shortcutsByLabel(visible(root, "wsCellLayout"));
        if (!workspace.keySet().equals(labels)) return false;
        for (Shortcut shortcut : workspace.values()) {
            if (!shortcut.selection().equals(selectedLabels.contains(shortcut.label()) ? selected : unselected)) return false;
        }
        AccessibilityNodeInfo dock = visible(root, "hotseat_layout");
        return dock != null && shortcuts(dock).stream().allMatch(shortcut -> shortcut.selection().equals(unselected));
    }

    private static Map<String, Shortcut> shortcutsByLabel(AccessibilityNodeInfo grid) {
        Map<String, Shortcut> result = new HashMap<>();
        if (grid != null) {
            for (Shortcut shortcut : shortcuts(grid)) {
                if (result.put(shortcut.label(), shortcut) != null) return Map.of();
            }
        }
        return result;
    }

    private static boolean isExtra(Shortcut shortcut, Set<HomeApp> seen) {
        for (HomeApp app : APPS) {
            if (!shortcut.folder() && app.labels().contains(shortcut.label())) return !seen.add(app);
        }
        return true;
    }

    private Page removeOtherShortcuts(Page main) throws IOException {
        for (int i = 0; i < 63; i++) {
            List<Shortcut> current = shortcuts(main.grid());
            Set<HomeApp> seen = new HashSet<>();
            Shortcut extra = null;
            for (Shortcut shortcut : current) {
                if (isExtra(shortcut, seen)) {
                    extra = shortcut;
                    break;
                }
            }
            if (extra == null) return main;
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
        Rect target = menuAction(labels);
        if (target == null) throw new IOException(failure);
        tap(target);
    }

    private Rect menuAction(Set<String> labels) {
        AccessibilityNodeInfo menu = ui.getRootInActiveWindow();
        if (menu == null) return null;
        Rect target = null;
        for (AccessibilityNodeInfo action : menu.findAccessibilityNodeInfosByViewId(id("global_option_label"))) {
            if (action.isVisibleToUser() && labels.contains(text(action))) {
                if (target != null) return null;
                target = bounds(action);
            }
        }
        return target == null || target.isEmpty() ? null : target;
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
        return readPage(ui.getRootInActiveWindow(), grid);
    }

    private static Page readPage(AccessibilityNodeInfo root, AccessibilityNodeInfo grid) throws IOException {
        AccessibilityNodeInfo indicator = visible(root, "workspace_page_indicator");
        AccessibilityNodeInfo pages = indicator == null ? null : indicator.getChild(0);
        if (pages == null) return pageFromWorkspace(root, grid);
        for (int i = 0; i < pages.getChildCount(); i++) {
            AccessibilityNodeInfo page = pages.getChild(i);
            if (page != null && page.isSelected()) return new Page(grid, i, pages.getChildCount());
        }
        return pageFromWorkspace(root, grid);
    }

    private static Page pageFromWorkspace(AccessibilityNodeInfo root, AccessibilityNodeInfo grid) throws IOException {
        AccessibilityNodeInfo workspace = visible(root, "workspace_fast_recycler_view");
        if (workspace == null) return new Page(grid, 0, 1);
        // One UI can hide the page indicator or omit its selected dot on a single-page home.
        // The workspace exposes page containers in order even when their grids are off-screen.
        AccessibilityNodeInfo current = grid.getParent();
        int index = -1, count = 0;
        for (int i = 0; i < workspace.getChildCount(); i++) {
            AccessibilityNodeInfo page = workspace.getChild(i);
            if (page == null || page.findAccessibilityNodeInfosByViewId(id("wsCellLayout")).isEmpty()) continue;
            if (page.equals(current)) index = count;
            count++;
        }
        if (index < 0) throw new IOException("현재 홈 페이지 확인 필요");
        return new Page(grid, index, count);
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
        String resource = name.startsWith("android:") ? name : id(name);
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(resource))
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
            String label = title == null ? "" : text(title);
            CharSequence description = child.getContentDescription();
            String selection = "";
            if (description != null && description.toString().startsWith(label + ",")) {
                String value = description.toString();
                selection = value.substring(value.lastIndexOf(',') + 1).strip();
            }
            result.add(new Shortcut(label, bounds(child), folder, selection));
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
        List<String> command = new ArrayList<>(List.of("/system/bin/cmd", "input"));
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
