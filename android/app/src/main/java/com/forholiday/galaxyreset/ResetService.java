package com.forholiday.galaxyreset;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.IOException;

public class ResetService extends Service {
    static volatile boolean alive;
    static final String CHANNEL = "reset_progress";
    static final int NOTIFICATION = 19;
    private RunState state;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate(); state = new RunState(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "초기화 진행", NotificationManager.IMPORTANCE_LOW));
    }
    Notification notification(String message, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_reset)
            .setContentTitle("갤럭시 초기화").setContentText(message).setContentIntent(pending).setOngoing(ongoing).setOnlyAlertOnce(true);
        if (ongoing) builder.setProgress(19, state.completed(), false);
        return builder.build();
    }
    void notifyProgress(String message) { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message, true)); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (alive) return START_NOT_STICKY;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        alive = true;
        if (intent.getBooleanExtra("fresh", false)) state.begin(intent.getStringExtra("locale"));
        int from = intent.getIntExtra("from", 0);
        boolean single = intent.getBooleanExtra("single", false);
        if (from < 0 || from >= 19) { alive = false; stopSelf(); return START_NOT_STICKY; }
        state.phase("running", from, "접속·실행 권한 확인 중");
        startForeground(NOTIFICATION, notification("실행 준비 중", true));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GalaxyReset:cleanup");
        wakeLock.acquire(45 * 60 * 1000L);
        ShellConnection.INSTANCE.context = getApplicationContext();
        ShellConnection.INSTANCE.connect(false);
        new Thread(() -> execute(from, single), "reset-pipeline").start();
        return START_NOT_STICKY; // Never restart a destructive run automatically.
    }
    void execute(int from, boolean single) {
        ShellConnection shell = ShellConnection.INSTANCE;
        ResetPipeline pipeline = new ResetPipeline(shell, state::recovery, state.pending());
        int current = from;
        try {
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!shell.ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
            shell.checkUid();
            if (!pipeline.required("am get-current-user").output().trim().equals("0"))
                throw new IOException("소유자(사용자 0) 화면에서만 초기화할 수 있습니다.");
            if (android.os.Process.myUserHandle().hashCode() != 0) throw new IOException("소유자 프로필에 앱을 설치해주세요.");
            String screenTimeout = pipeline.configureScreenTimeout();
            state.prefs.edit().putString("summary", screenTimeout).commit();
            pipeline.restorePending();
            for (int i = from; i < (single ? from + 1 : 19); i++) {
                current = i;
                state.phase("running", i, ResetPipeline.LABELS[i]); state.step(i, "진행 중", "");
                notifyProgress((i + 1) + "/19 · " + ResetPipeline.LABELS[i]);
                try {
                    pipeline.step(i, state.locale(), Build.MODEL);
                    state.step(i, pipeline.warnings.isEmpty() ? "완료" : (pipeline.manual ? "수동 확인" : "실패"), String.join("\n", pipeline.warnings) + (pipeline.notes.isEmpty() ? "" : (pipeline.warnings.isEmpty() ? "" : "\n") + String.join("\n", pipeline.notes)));
                } catch (ResetPipeline.ConnectionLost e) { throw e; }
                catch (Exception e) {
                    if (!shell.ready()) throw new ResetPipeline.ConnectionLost("Shizuku 연결 끊김");
                    state.step(i, "실패", e.getMessage() == null ? "단계 실행 실패" : e.getMessage());
                    // Keep a session-clear failure visible across later single-step retries.
                    if (i == 1 && (pipeline.failedClears.contains("com.nhn.android.nmap") || pipeline.failedClears.contains("com.google.android.apps.tachyon")))
                        state.step(2, "실패", "로그인·저장 데이터 초기화 재확인 필요");
                    // Restoration failure must block further destructive work until recovered.
                    if (!pipeline.pending.isEmpty()) throw new IOException("앱 재활성화 복구가 필요합니다.");
                }
            }
            if (pipeline.summaryText.isEmpty()) {
                try {
                    pipeline.step(17, state.locale(), Build.MODEL);
                    state.step(17, pipeline.warnings.isEmpty() ? "완료" : "실패", String.join("\n", pipeline.warnings));
                } catch (ResetPipeline.ConnectionLost e) { throw e; }
                catch (Exception e) { state.step(17, "실패", "최종 검증 실패 · " + e.getMessage()); }
            }
            if (pipeline.finalAccountCount == 0 && !pipeline.failedClears.contains("com.nhn.android.nmap") && !pipeline.failedClears.contains("com.google.android.apps.tachyon") && !state.status(1).equals("대기"))
                state.step(1, "완료", "최종 검증: 비삼성 계정 0개 확인");
            if (pipeline.finalUserAppsClear && state.status(0).equals("실패"))
                state.step(0, "완료", "최종 검증: 삭제 대상 앱 없음");
            state.prefs.edit().putString("summary", screenTimeout + "\n" + pipeline.summaryText).commit();
            state.phase(state.issues() ? "issues" : "done", 18, state.issues() ? "일부 실패 · 수동 확인 항목을 확인하세요." : "19단계 완료");
        } catch (Exception e) {
            state.phase("interrupted", current, e.getMessage() == null ? "실행 중단" : e.getMessage());
        } finally {
            // Shell identity can bring the UI back after launcher / recent tasks operations.
            try { shell.run("am start --user 0 -n " + getPackageName() + "/.MainActivity", 15); } catch (Exception ignored) {}
            main.post(() -> {
                alive = false;
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                getSystemService(NotificationManager.class).cancel(NOTIFICATION);
                getSystemService(NotificationManager.class).cancel(AdbPairingService.COMPLETION_NOTIFICATION);
                stopSelf();
            });
        }
    }
    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
