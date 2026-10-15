package com.forholiday.galaxyreset;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

final class RunState {
    final SharedPreferences prefs;
    RunState(Context context) { prefs = context.getSharedPreferences("run", Context.MODE_PRIVATE); }
    String phase() { return prefs.getString("phase", "idle"); }
    int index() { return prefs.getInt("index", 0); }
    boolean homeOnNextLaunch() { return prefs.getBoolean("home_after_shutdown", false); }
    void homeOnNextLaunch(boolean value) {
        if (!prefs.edit().putBoolean("home_after_shutdown", value).commit())
            throw new IllegalStateException("화면 상태를 저장하지 못했습니다.");
    }
    String locale() { return prefs.getString("locale", "ja-JP"); }
    String status(int step) { return prefs.getString("status" + step, "대기"); }
    String detail(int step) { return prefs.getString("detail" + step, ""); }
    Set<String> pending() { return new LinkedHashSet<>(prefs.getStringSet("restore", Set.of())); }
    void recovery(Set<String> packages) { if (!prefs.edit().putStringSet("restore", new LinkedHashSet<>(packages)).commit())
            throw new IllegalStateException("앱 복구 기록 저장 실패 · 저장 공간을 확인하세요."); }
    void phase(String phase, int index, String message) {
        prefs.edit().putString("phase", phase).putInt("index", index).putString("message", message).commit();
    }
    void step(int index, String status, String detail) {
        prefs.edit().putString("status" + index, status).putString("detail" + index, detail).commit();
    }
    void begin(String locale) {
        SharedPreferences.Editor editor = prefs.edit().putString("locale", locale).putString("summary", "");
        for (int i = 0; i < ResetPipeline.LABELS.length; i++) editor.remove("status" + i).remove("detail" + i);
        editor.commit(); // Keep restore journal until recovery succeeds.
    }
    int completed() {
        int count = 0;
        for (int i = 0; i < ResetPipeline.LABELS.length; i++) if (!status(i).equals("대기") && !status(i).equals("진행 중")) count++;
        return count;
    }
    boolean issues() {
        for (int i = 0; i < ResetPipeline.LABELS.length; i++) if (status(i).equals("실패") || status(i).equals("수동 확인")) return true;
        return !pending().isEmpty();
    }
    void detectInterruption() {
        if (phase().equals("running") && !ResetService.alive)
            phase("interrupted", index(), "앱 실행이 중단되었습니다. Shizuku 연결 후 현재 단계부터 재시도하세요.");
    }
}
