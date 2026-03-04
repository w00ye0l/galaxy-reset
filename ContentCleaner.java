import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import java.lang.reflect.Method;

/**
 * 비루트 환경에서 통화기록, SMS, 주소록을 삭제하는 헬퍼.
 *
 * app_process 컨텍스트에서 IContentProvider Binder를 직접 호출하여
 * ContentProvider의 delete를 수행합니다.
 *
 * 사용법: app_process /system/bin ContentCleaner
 */
public class ContentCleaner {

    private static final String[][] TARGETS = {
        {"CALL_LOG",  "content://call_log/calls"},
        {"SMS",       "content://sms"},
        {"CONTACTS",  "content://com.android.contacts/raw_contacts"},
    };

    public static void main(String[] args) {
        boolean anyFallback = false;

        // 방법 1: Binder 직접 호출 (주 경로)
        Object am = null;
        try {
            am = getActivityManager();
        } catch (Exception e) {
            System.err.println("WARN:ActivityManager 획득 실패: " + e.getMessage());
        }

        if (am != null) {
            for (String[] target : TARGETS) {
                if (!deleteViaBinder(am, target[0], target[1])) {
                    anyFallback = true;
                }
            }
        } else {
            // 방법 2: ActivityThread → ContentResolver 폴백
            try {
                ContentResolver resolver = acquireContentResolver();
                for (String[] target : TARGETS) {
                    if (!deleteViaResolver(resolver, target[0], target[1])) {
                        anyFallback = true;
                    }
                }
            } catch (Exception e) {
                System.err.println("FAIL:모든 접근 방식 실패: " + e.getMessage());
                // 각 타겟에 FALLBACK 출력
                for (String[] target : TARGETS) {
                    System.out.println("FALLBACK:" + target[0] + ":All approaches failed");
                }
                System.exit(1);
            }
        }

        if (anyFallback) {
            System.out.println("PARTIAL: Some targets needed fallback");
        } else {
            System.out.println("SUCCESS: All content cleaned");
        }
    }

    // ── Binder 직접 호출 방식 (주 경로) ──

    private static boolean deleteViaBinder(Object am, String label, String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            String authority = uri.getAuthority();

            Object holder = getContentProviderExternal(am, authority);
            if (holder == null) {
                System.out.println("FALLBACK:" + label + ":getContentProviderExternal returned null");
                return false;
            }

            // holder.provider → IContentProvider
            Object provider = holder.getClass().getField("provider").get(holder);
            if (provider == null) {
                System.out.println("FALLBACK:" + label + ":provider is null");
                return false;
            }

            int result = callProviderDelete(provider, uri);
            System.out.println("OK:" + label + ":" + result + " rows deleted");

            // provider 해제
            removeContentProviderExternal(am, authority);
            return true;

        } catch (Exception e) {
            System.out.println("FALLBACK:" + label + ":" + e.getMessage());
            return false;
        }
    }

    // ── ContentResolver 방식 (폴백) ──

    private static boolean deleteViaResolver(ContentResolver resolver, String label, String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            int deleted = resolver.delete(uri, null, null);
            System.out.println("OK:" + label + ":" + deleted + " rows deleted");
            return true;
        } catch (SecurityException se) {
            System.out.println("PERMISSION_DENIED:" + label + ":" + se.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("FALLBACK:" + label + ":" + e.getMessage());
            return false;
        }
    }

    private static ContentResolver acquireContentResolver() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");

        // systemMain → getSystemContext
        try {
            Method systemMain = atClass.getMethod("systemMain");
            Object at = systemMain.invoke(null);
            if (at != null) {
                Method getCtx = atClass.getMethod("getSystemContext");
                Context ctx = (Context) getCtx.invoke(at);
                if (ctx != null) {
                    ContentResolver cr = ctx.getContentResolver();
                    if (cr != null) return cr;
                }
            }
        } catch (Exception e) {
            System.err.println("WARN:systemMain failed: " + e.getMessage());
        }

        throw new RuntimeException("Cannot acquire ContentResolver");
    }

    // ── IActivityManager 유틸리티 ──

    private static Object getActivityManager() throws Exception {
        // Android 12+: ActivityManager.getService()
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Method getService = amClass.getMethod("getService");
            Object am = getService.invoke(null);
            if (am != null) return am;
        } catch (Exception ignored) {}

        // 레거시: ActivityManagerNative.getDefault()
        Class<?> amnClass = Class.forName("android.app.ActivityManagerNative");
        Method getDefault = amnClass.getMethod("getDefault");
        return getDefault.invoke(null);
    }

    private static Object getContentProviderExternal(Object am, String authority) throws Exception {
        // 시그니처 1 (Android 12+): getContentProviderExternal(String, int, IBinder, String)
        try {
            Method m = am.getClass().getMethod("getContentProviderExternal",
                String.class, int.class, Class.forName("android.os.IBinder"), String.class);
            return m.invoke(am, authority, 0, null, "content_cleaner");
        } catch (NoSuchMethodException ignored) {}

        // 시그니처 2 (Android 10-11): getContentProviderExternal(String, int, IBinder)
        try {
            Method m = am.getClass().getMethod("getContentProviderExternal",
                String.class, int.class, Class.forName("android.os.IBinder"));
            return m.invoke(am, authority, 0, null);
        } catch (NoSuchMethodException ignored) {}

        throw new RuntimeException("getContentProviderExternal method not found");
    }

    private static int callProviderDelete(Object provider, Uri uri) throws Exception {
        // 시그니처 1 (Android 14+): delete(String, String, Uri, Bundle)
        try {
            Class<?> bundleClass = Class.forName("android.os.Bundle");
            Method m = provider.getClass().getMethod("delete",
                String.class, String.class, Uri.class, bundleClass);
            return (int) m.invoke(provider, "com.android.shell", null, uri, null);
        } catch (NoSuchMethodException ignored) {}

        // 시그니처 2 (Android 11-13): delete(String, Uri, Bundle)
        try {
            Class<?> bundleClass = Class.forName("android.os.Bundle");
            Method m = provider.getClass().getMethod("delete",
                String.class, Uri.class, bundleClass);
            return (int) m.invoke(provider, "com.android.shell", uri, null);
        } catch (NoSuchMethodException ignored) {}

        // 시그니처 3 (레거시): delete(String, Uri, String, String[])
        try {
            Method m = provider.getClass().getMethod("delete",
                String.class, Uri.class, String.class, String[].class);
            return (int) m.invoke(provider, "com.android.shell", uri, null, null);
        } catch (NoSuchMethodException ignored) {}

        throw new RuntimeException("IContentProvider.delete method not found");
    }

    private static void removeContentProviderExternal(Object am, String authority) {
        try {
            Method m = am.getClass().getMethod("removeContentProviderExternal",
                String.class, Class.forName("android.os.IBinder"));
            m.invoke(am, authority, null);
        } catch (Exception ignored) {}
    }
}
