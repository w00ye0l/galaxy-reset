import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 배경화면 설정 유틸리티 (app_process로 실행)
 * Android 10~16 호환 — 리플렉션으로 IWallpaperManager 바인더 직접 호출
 *
 * 사용법: CLASSPATH=/data/local/tmp/wallpaper_setter.dex app_process /system/bin WallpaperSetter /path/to/image.png
 */
public class WallpaperSetter {

    private static final int FLAG_SYSTEM = 1;
    private static final int FLAG_LOCK = 2;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("FAIL:NO_ARG:Usage: WallpaperSetter <image_path>");
            return;
        }

        String imagePath = args[0];
        if (!new java.io.File(imagePath).exists()) {
            System.out.println("FAIL:FILE_NOT_FOUND:" + imagePath);
            return;
        }

        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }

        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "wallpaper");

            Class<?> stubClass = Class.forName("android.app.IWallpaperManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            Object wm = asInterface.invoke(null, binder);

            setWallpaperForFlag(wm, imagePath, FLAG_SYSTEM, "HOME");
            setWallpaperForFlag(wm, imagePath, FLAG_LOCK, "LOCK");

            System.out.println("SUCCESS:WALLPAPER_SET:" + imagePath);
        } catch (Exception e) {
            System.out.println("FAIL:EXCEPTION:" + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static void setWallpaperForFlag(Object wm, String imagePath, int flag, String label) {
        // Android 16 (11-arg): setWallpaper(String, String, WallpaperDescription, boolean, Bundle, int, callback, int, int, boolean, Bundle)
        if (tryAndroid16(wm, imagePath, flag, label)) return;
        // Samsung One UI (12-arg): setWallpaper(String, String, int[], List, boolean, Bundle, int, callback, int, int, boolean, Bundle)
        if (trySamsung12arg(wm, imagePath, flag, label)) return;
        // Android 14-15 (8-arg): setWallpaper(String, String, Rect, boolean, Bundle, int, callback, int)
        if (tryAndroid14(wm, imagePath, flag, label)) return;
        // Android 12-13 (7-arg): setWallpaper(String, Rect, boolean, Bundle, int, callback, int)
        if (tryAndroid12(wm, imagePath, flag, label)) return;

        System.out.println("FAIL:" + label + ":No compatible setWallpaper method");
    }

    private static boolean tryAndroid16(Object wm, String imagePath, int flag, String label) {
        try {
            // WallpaperDescription.Builder().build()
            Class<?> builderClass = Class.forName("android.app.wallpaper.WallpaperDescription$Builder");
            Constructor<?> builderCtor = builderClass.getConstructor();
            Object builder = builderCtor.newInstance();
            Method buildMethod = builderClass.getMethod("build");
            Object wallpaperDesc = buildMethod.invoke(builder);

            Class<?> descClass = Class.forName("android.app.wallpaper.WallpaperDescription");
            Class<?> callbackClass = Class.forName("android.app.IWallpaperManagerCallback");

            Method setWallpaper = wm.getClass().getMethod("setWallpaper",
                String.class, String.class, descClass,
                boolean.class, Bundle.class, int.class,
                callbackClass, int.class, int.class,
                boolean.class, Bundle.class);

            Bundle extras = new Bundle();
            Bundle outParams = new Bundle();
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) setWallpaper.invoke(wm,
                "WallpaperSetter", "com.android.shell", wallpaperDesc,
                false, extras, flag,
                null, 0, 0,
                false, outParams);

            if (pfd != null) {
                writeImageToPfd(pfd, imagePath);
                System.out.println("OK:" + label + ":Android16");
                return true;
            }
        } catch (ClassNotFoundException e) {
            // WallpaperDescription not available — not Android 16
        } catch (NoSuchMethodException e) {
            // Different signature
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            System.out.println("WARN:" + label + ":Android16:" + msg);
        }
        return false;
    }

    private static boolean trySamsung12arg(Object wm, String imagePath, int flag, String label) {
        try {
            Class<?> callbackClass = Class.forName("android.app.IWallpaperManagerCallback");
            Method setWallpaper = wm.getClass().getMethod("setWallpaper",
                String.class, String.class, int[].class, List.class,
                boolean.class, Bundle.class, int.class,
                callbackClass, int.class, int.class,
                boolean.class, Bundle.class);

            Bundle extras = new Bundle();
            Bundle outParams = new Bundle();
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) setWallpaper.invoke(wm,
                "WallpaperSetter", "com.android.shell", (int[]) null, (List<?>) null,
                false, extras, flag,
                null, 0, 0,
                false, outParams);

            if (pfd != null) {
                writeImageToPfd(pfd, imagePath);
                System.out.println("OK:" + label + ":Samsung12arg");
                return true;
            }
        } catch (NoSuchMethodException e) {
            // not Samsung — try next
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            System.out.println("WARN:" + label + ":Samsung12arg:" + msg);
        }
        return false;
    }

    private static boolean tryAndroid14(Object wm, String imagePath, int flag, String label) {
        try {
            Class<?> callbackClass = Class.forName("android.app.IWallpaperManagerCallback");
            Method setWallpaper = wm.getClass().getMethod("setWallpaper",
                String.class, String.class, Rect.class,
                boolean.class, Bundle.class, int.class,
                callbackClass, int.class);

            ParcelFileDescriptor pfd = (ParcelFileDescriptor) setWallpaper.invoke(wm,
                "WallpaperSetter", "com.android.shell",
                null, false, null, flag, null, 0);

            if (pfd != null) {
                writeImageToPfd(pfd, imagePath);
                System.out.println("OK:" + label + ":Android14-15");
                return true;
            }
        } catch (NoSuchMethodException e) {
            // try next
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            System.out.println("WARN:" + label + ":Android14:" + msg);
        }
        return false;
    }

    private static boolean tryAndroid12(Object wm, String imagePath, int flag, String label) {
        try {
            Class<?> callbackClass = Class.forName("android.app.IWallpaperManagerCallback");
            Method setWallpaper = wm.getClass().getMethod("setWallpaper",
                String.class, Rect.class, boolean.class,
                Bundle.class, int.class, callbackClass, int.class);

            ParcelFileDescriptor pfd = (ParcelFileDescriptor) setWallpaper.invoke(wm,
                "WallpaperSetter", null, false, null, flag, null, 0);

            if (pfd != null) {
                writeImageToPfd(pfd, imagePath);
                System.out.println("OK:" + label + ":Android12-13");
                return true;
            }
        } catch (NoSuchMethodException e) {
            // not available
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            System.out.println("WARN:" + label + ":Android12:" + msg);
        }
        return false;
    }

    private static void writeImageToPfd(ParcelFileDescriptor pfd, String imagePath) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
             FileInputStream fis = new FileInputStream(imagePath)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.flush();
        } finally {
            pfd.close();
        }
    }
}
