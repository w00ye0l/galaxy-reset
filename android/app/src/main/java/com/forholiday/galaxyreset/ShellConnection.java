package com.forholiday.galaxyreset;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import rikka.shizuku.Shizuku;

final class ShellConnection implements ResetPipeline.Bridge {
    static final ShellConnection INSTANCE = new ShellConnection();
    volatile IShellService remote;
    volatile String message = "Shizuku를 무선 디버깅으로 시작해주세요.";
    private boolean binding;
    private long retryAt;
    private int attempt;
    enum State { STOPPED, PERMISSION, CONNECTING, RETRY, READY }
    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
        new ComponentName(BuildConfig.APPLICATION_ID, ShellService.class.getName()))
        .daemon(false).processNameSuffix("reset_shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IShellService.Stub.asInterface(binder);
            try { binder.linkToDeath(() -> new Handler(Looper.getMainLooper()).post(() -> {
                if (remote != null && remote.asBinder() == binder) lost();
            }), 0); }
            catch (RemoteException e) { lost(); return; }
            binding = false; retryAt = 0; message = "연결됨 · 초기화를 실행할 수 있습니다.";
        }
        @Override public void onServiceDisconnected(ComponentName name) { lost(); }
    };
    void lost() { remote = null; binding = false; retryAt = 0; attempt++; message = Shizuku.pingBinder()
        ? "앱의 권한 연결이 끊겼습니다. 다시 연결을 눌러주세요."
        : "Shizuku가 꺼져 있습니다. Shizuku에서 무선 디버깅 ‘시작’을 눌러주세요."; }
    State state() {
        if (!Shizuku.pingBinder()) return State.STOPPED;
        try { if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.PERMISSION; }
        catch (Exception ignored) { return State.STOPPED; }
        if (ready()) return State.READY;
        return binding ? State.CONNECTING : State.RETRY;
    }
    void connect(boolean requestPermission) {
        try {
            if (!Shizuku.pingBinder()) { lost(); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                remote = null; binding = false; retryAt = 0; attempt++;
                message = "Shizuku에서 초기화 앱 권한을 허용해주세요.";
                if (requestPermission && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(10);
                return;
            }
            if (remote == null && !binding && (requestPermission || SystemClock.elapsedRealtime() >= retryAt)) {
                binding = true; message = "shell 서비스 연결 중…";
                final int currentAttempt = ++attempt;
                Shizuku.bindUserService(args, connection);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (remote == null && binding && attempt == currentAttempt) {
                        binding = false; retryAt = SystemClock.elapsedRealtime() + 5000;
                        message = "연결 시간이 초과됐습니다. 다시 연결을 누르거나 Shizuku 상태를 확인해주세요.";
                    }
                }, 15000);
            }
        } catch (Exception e) { binding = false; retryAt = SystemClock.elapsedRealtime() + 5000; message = "연결 실패: " + e.getMessage(); }
    }
    boolean ready() {
        IShellService service = remote;
        if (service != null && (!Shizuku.pingBinder() || !service.asBinder().pingBinder())) { lost(); return false; }
        if (service != null) try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { remote = null; binding = false; return false; }
        } catch (Exception ignored) { lost(); return false; }
        return service != null;
    }
    private IShellService require() throws ResetPipeline.ConnectionLost {
        IShellService service = remote;
        if (service == null || !service.asBinder().pingBinder()) throw new ResetPipeline.ConnectionLost("Shizuku 연결 끊김");
        return service;
    }
    @Override public ResetPipeline.Result run(String command, int seconds) throws Exception {
        try {
            Bundle result = require().execute(command, seconds);
            return new ResetPipeline.Result(result.getInt("code"), result.getString("output", ""), result.getBoolean("truncated"));
        } catch (RemoteException e) {
            if (!ready() || !Shizuku.pingBinder()) throw new ResetPipeline.ConnectionLost("Shizuku 연결 끊김");
            throw new IOException("shell 실행 실패: " + e.getMessage());
        }
    }
    void checkUid() throws Exception {
        if (require().uid() != 2000) throw new IOException("Shizuku를 루트가 아닌 무선 디버깅으로 시작해주세요.");
    }
    Context context;
    @Override public void copy(String asset, String destination) throws Exception {
        // Stream through a file descriptor; shell cannot read the app's private cache by path.
        File cache = new File(context.getCacheDir(), "transfer-" + asset);
        try {
            try (InputStream input = context.getAssets().open(asset); OutputStream output = new FileOutputStream(cache)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    require().copyAsset(descriptor, destination);
                }
                if (run("test -s " + ResetPipeline.quote(destination), 10).code() == 0) return;
            }
            throw new IOException("자산 복사 검증 실패: " + asset);
        } catch (RemoteException e) {
            if (!ready()) throw new ResetPipeline.ConnectionLost("자산 복사 중 연결 끊김");
            throw new IOException("자산 복사 실패: " + e.getMessage());
        } finally { cache.delete(); }
    }
}
