package com.forholiday.galaxyreset;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Samsung Settings fallback for a NAVER authenticator that refuses background removal. */
final class NaverAccountCleanup {
    static final String SETTINGS = "com.android.settings";
    static final String FILE = ResetPipeline.TMP + "naver-account-ui.xml";
    static final Set<String> REMOVE = Set.of("계정 삭제", "アカウントを削除", "Remove account",
        "删除账号", "删除帐号", "移除账号", "移除帐号", "移除帳戶", "移除帳號", "刪除帳戶", "刪除帳號");
    static final Set<String> CONFIRM = Set.of("삭제", "削除", "Remove", "Remove account", "删除", "移除", "刪除");
    final ResetPipeline pipeline;
    NaverAccountCleanup(ResetPipeline pipeline) { this.pipeline = pipeline; }

    static Document parse(String xml) throws Exception {
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("계정 설정 화면 형식 확인 필요");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
    static List<Element> nodes(Document document) {
        List<Element> nodes = new ArrayList<>();
        NodeList all = document.getElementsByTagName("node");
        for (int i = 0; i < all.getLength(); i++) nodes.add((Element) all.item(i));
        return nodes;
    }
    static boolean owned(Element node) { return SETTINGS.equals(node.getAttribute("package")); }
    static Element find(Document document, String id, Set<String> labels) {
        for (Element node : nodes(document))
            if (owned(node) && id.equals(node.getAttribute("resource-id"))
                && labels.contains(node.getAttribute("text"))) return node;
        return null;
    }
    static Element naverRow(Document document) {
        return find(document, "android:id/summary", Set.of("NAVER"));
    }
    static Element removeButton(Document document) {
        if (find(document, SETTINGS + ":id/entity_header_summary", Set.of("NAVER")) == null) return null;
        return find(document, SETTINGS + ":id/button", REMOVE);
    }
    static Element confirmButton(Document document) {
        boolean title = false;
        for (Element node : nodes(document)) {
            if (!owned(node) || !node.getAttribute("resource-id").endsWith(":id/alertTitle")) continue;
            String text = node.getAttribute("text");
            for (String label : REMOVE) if (text.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) title = true;
        }
        return title ? find(document, "android:id/button1", CONFIRM) : null;
    }
    static int[] center(Element node) throws IOException {
        if (node == null || !owned(node)) throw new IOException("네이버 계정 제거 버튼 확인 필요");
        Matcher match = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(node.getAttribute("bounds"));
        if (!match.matches()) throw new IOException("계정 설정 화면 좌표 확인 필요");
        int left = Integer.parseInt(match.group(1)), top = Integer.parseInt(match.group(2));
        int right = Integer.parseInt(match.group(3)), bottom = Integer.parseInt(match.group(4));
        if (right <= left || bottom <= top || !"true".equals(node.getAttribute("enabled")))
            throw new IOException("계정 설정 화면을 켜고 잠금을 해제해주세요.");
        return new int[]{left + (right - left) / 2, top + (bottom - top) / 2};
    }
    String run(String command, int timeout) throws Exception {
        ResetPipeline.Result result = pipeline.raw(command, timeout);
        if (result.code() != 0 || result.truncated() || result.output().contains("SecurityException"))
            throw new IOException("네이버 계정 제거 명령 실행 실패");
        return result.output();
    }
    Document screen() throws Exception {
        String xml = run("uiautomator dump --compressed " + ResetPipeline.quote(FILE)
            + " >/dev/null && cat " + ResetPipeline.quote(FILE), 25);
        if (!xml.trim().startsWith("<?xml") && !xml.trim().startsWith("<hierarchy"))
            throw new IOException("네이버 계정 설정 화면 조회 실패");
        return parse(xml);
    }
    void tap(Element node) throws Exception {
        int[] point = center(node);
        run("input tap " + point[0] + " " + point[1], 10);
        pipeline.pause(350);
    }
    int remaining() throws Exception { return ResetPipeline.naverAccounts(pipeline.required("dumpsys account").output()); }
    void remove() throws Exception {
        int count = remaining();
        if (count == 0) return;
        try {
            run("mkdir -p " + ResetPipeline.quote(ResetPipeline.TMP), 10);
            // Only navigate Settings. Never change authentication policy or accept a lock screen.
            for (int attempt = 0; attempt < 10 && count > 0; attempt++) {
                String start = run("am start -W --user 0 -a android.settings.SYNC_SETTINGS", 20);
                if (!start.contains("Status: ok")) throw new IOException("계정 관리 화면 실행 실패");
                Element row = naverRow(screen());
                // A long account list may need a few scrolls; never select another provider.
                for (int scroll = 0; row == null && scroll < 5; scroll++) {
                    Document page = screen();
                    Element list = null;
                    for (Element node : nodes(page)) if (owned(node) && "true".equals(node.getAttribute("scrollable"))) { list = node; break; }
                    if (list == null) break;
                    int[] midpoint = center(list);
                    run("input swipe " + midpoint[0] + " " + (midpoint[1] + 150)
                        + " " + midpoint[0] + " " + Math.max(0, midpoint[1] - 150) + " 300", 10);
                    row = naverRow(screen());
                }
                tap(row);
                tap(removeButton(screen()));
                tap(confirmButton(screen()));
                int after = count;
                for (int wait = 0; wait < 10 && after >= count; wait++) { pipeline.pause(500); after = remaining(); }
                if (after >= count) throw new IOException("네이버 계정 제거가 거부되었거나 확인되지 않았습니다.");
                count = after;
            }
            if (count > 0) throw new IOException("네이버 계정이 남아 있습니다.");
        } finally {
            try { pipeline.raw("rm -f " + ResetPipeline.quote(FILE), 10); } catch (Exception ignored) {}
            try { pipeline.raw("am start --user 0 -n " + ResetPipeline.SELF + "/.MainActivity", 15); } catch (Exception ignored) {}
        }
    }
}
