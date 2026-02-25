import android.content.res.Configuration;
import android.os.LocaleList;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 비루트 환경에서 기기 언어를 설정하는 헬퍼.
 *
 * 사용법: app_process /system/bin LocaleChanger ja-JP ko-KR en-US
 *   → 일본어를 기본으로, 한국어/영어를 보조 언어로 설정
 */
public class LocaleChanger {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: LocaleChanger <locale> [locale2] [locale3] ...");
            System.exit(1);
        }

        try {
            Locale[] locales = new Locale[args.length];
            for (int i = 0; i < args.length; i++) {
                locales[i] = parseLocale(args[i]);
            }

            LocaleList localeList = new LocaleList(locales);

            // 리플렉션으로 IActivityManager 접근 (hidden API)
            Class<?> amClass = Class.forName("android.app.ActivityManagerNative");
            Method getDefault = amClass.getMethod("getDefault");
            Object am = getDefault.invoke(null);

            Method getConfig = am.getClass().getMethod("getConfiguration");
            Configuration config = (Configuration) getConfig.invoke(am);

            config.setLocales(localeList);

            Method updateConfig = am.getClass().getMethod(
                "updatePersistentConfiguration", Configuration.class);
            updateConfig.invoke(am, config);

            System.out.println("SUCCESS: Locale set to " + localeList.toLanguageTags());
        } catch (Exception e) {
            System.err.println("FAIL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Locale parseLocale(String tag) {
        String[] parts = tag.replace("_", "-").split("-");
        if (parts.length >= 2) {
            return new Locale(parts[0], parts[1]);
        }
        return new Locale(parts[0]);
    }
}
