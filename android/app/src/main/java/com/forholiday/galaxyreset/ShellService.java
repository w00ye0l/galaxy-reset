package com.forholiday.galaxyreset;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Instantiated by Shizuku in its shell process, not an Android Service. */
public class ShellService extends IShellService.Stub {
    public ShellService() {}
    @Override public int uid() { return Os.getuid(); }
    @Override public void destroy() { System.exit(0); }
    @Override public Bundle execute(String command, int timeoutSeconds) throws RemoteException {
        Process process = null;
        try {
            if (uid() != 2000) throw new IOException("무선 디버깅 shell(uid 2000)로 시작하세요.");
            process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
            final InputStream input = process.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (input) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) != -1) {
                        // Drain even after the cap to avoid subprocess pipe deadlocks / Binder overflow.
                        synchronized (buffer) {
                            int keep = Math.min(count, Math.max(0, 64000 - buffer.size()));
                            buffer.write(bytes, 0, keep);
                        }
                    }
                } catch (IOException ignored) {}
            }, "shell-output");
            reader.setDaemon(true); reader.start();
            boolean finished = process.waitFor(Math.min(600, Math.max(1, timeoutSeconds)), TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            reader.join(2000);
            Bundle result = new Bundle();
            result.putInt("code", finished ? process.exitValue() : 124);
            synchronized (buffer) { result.putString("output", buffer.toString(StandardCharsets.UTF_8.name())); }
            result.putBoolean("truncated", buffer.size() >= 64000);
            return result;
        } catch (Exception e) { throw new RemoteException(e.toString()); }
        finally { if (process != null) process.destroy(); }
    }
    @Override public void copyAsset(ParcelFileDescriptor source, String destination) throws RemoteException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source)) {
            if (uid() != 2000) throw new IOException("shell 권한 필요");
            if (!destination.matches("/data/local/tmp/galaxy-reset/[A-Za-z0-9_.-]+") &&
                !destination.matches("/sdcard/DCIM/ForHoliday/S2[3-6]\\.png"))
                throw new IOException("허용되지 않은 자산 경로");
            File target = new File(destination);
            File directory = target.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("폴더 생성 실패");
            File staging = new File(destination + ".part");
            try (FileOutputStream output = new FileOutputStream(staging)) {
                byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                output.getFD().sync();
            }
            // Android 14+ requires read-only dynamically loaded DEX files.
            Os.chmod(staging.getPath(), 0444);
            Os.rename(staging.getPath(), target.getPath());
        } catch (Exception e) { throw new RemoteException(e.toString()); }
    }
}
