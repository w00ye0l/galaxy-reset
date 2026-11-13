import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 비루트 환경에서 e-SIM 프로필을 조회, 비활성화, 삭제하는 헬퍼.
 *
 * 사용법:
 *   app_process /system/bin EsimManager list         → e-SIM 프로필 목록 출력
 *   app_process /system/bin EsimManager disable-all  → e-SIM 프로필 비활성화
 *   app_process /system/bin EsimManager delete-all   → e-SIM 비활성화 후 프로필 삭제
 */
public class EsimManager {

    public static void main(String[] args) {
        String action = (args.length > 0) ? args[0] : "delete-all";

        try {
            switch (action) {
                case "list":
                    listProfiles();
                    break;
                case "disable-all":
                    disableAllProfiles();
                    break;
                case "delete-all":
                    deleteAllProfiles();
                    break;
                case "verify-empty":
                    int count = findEsimProfiles(getISubProxy()).size();
                    System.out.println("ESIM_COUNT:" + count);
                    break;
                case "debug":
                    debugEuiccServices();
                    break;
                default:
                    System.err.println("Usage: EsimManager [list|disable-all|delete-all|verify-empty|debug]");
                    System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("FAIL:" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ────────────────────────────────────────────
    // e-SIM 프로필 조회
    // ────────────────────────────────────────────

    private static List<Object> listProfiles() throws Exception {
        List<Object> esimSubs = new ArrayList<>();
        Object isub = getISubProxy();
        List<?> allSubs = getActiveSubscriptions(isub);

        if (allSubs == null || allSubs.isEmpty()) {
            System.out.println("SUCCESS:NO_ESIM:No active subscriptions found");
            return esimSubs;
        }

        for (Object sub : allSubs) {
            try {
                Method isEmbedded = sub.getClass().getMethod("isEmbedded");
                boolean embedded = (boolean) isEmbedded.invoke(sub);
                if (!embedded) continue;

                esimSubs.add(sub);

                int subId = getInt(sub, "getSubscriptionId", "getSubId");
                String iccId = getString(sub, "getIccId");
                String carrier = getString(sub, "getCarrierName", "getDisplayName");

                System.out.println("ESIM:" + subId + ":"
                    + (iccId != null ? iccId : "N/A") + ":"
                    + (carrier != null ? carrier : "Unknown"));
            } catch (Exception e) {
                System.err.println("WARN:Sub check failed: " + e.getMessage());
            }
        }

        if (esimSubs.isEmpty()) {
            System.out.println("SUCCESS:NO_ESIM:No eSIM profiles found");
        } else {
            System.out.println("ESIM_COUNT:" + esimSubs.size());
        }
        return esimSubs;
    }

    // ────────────────────────────────────────────
    // e-SIM 프로필 비활성화
    // ────────────────────────────────────────────

    private static void disableAllProfiles() throws Exception {
        Object isub = getISubProxy();
        List<EsimInfo> esims = findEsimProfiles(isub);

        if (esims.isEmpty()) {
            System.out.println("SUCCESS:NO_ESIM:No eSIM profiles found");
            return;
        }

        System.out.println("ESIM_COUNT:" + esims.size());

        int disabledCount = 0;
        for (EsimInfo esim : esims) {
            boolean disabled = disableSubscription(isub, esim.subId);
            if (disabled) {
                disabledCount++;
                System.out.println("OK:ESIM_DISABLED:subId=" + esim.subId);
            } else {
                System.out.println("FAIL:ESIM_DISABLE:subId=" + esim.subId);
            }
        }

        if (disabledCount == esims.size()) {
            System.out.println("SUCCESS:ALL_ESIM_DISABLED:" + disabledCount + " profiles disabled");
        } else {
            System.out.println("PARTIAL:" + disabledCount + "/" + esims.size() + " profiles disabled");
        }
    }

    // ────────────────────────────────────────────
    // e-SIM 프로필 삭제 (비활성화 → 삭제)
    // ────────────────────────────────────────────

    private static void deleteAllProfiles() throws Exception {
        Object isub = getISubProxy();
        List<EsimInfo> esims = findEsimProfiles(isub);

        if (esims.isEmpty()) {
            System.out.println("SUCCESS:NO_ESIM:No eSIM profiles found");
            return;
        }

        System.out.println("ESIM_COUNT:" + esims.size());

        // Step 1: 모든 eSIM 비활성화
        for (EsimInfo esim : esims) {
            boolean disabled = disableSubscription(isub, esim.subId);
            if (disabled) {
                System.out.println("OK:ESIM_DISABLED:subId=" + esim.subId);
            } else {
                System.out.println("WARN:ESIM_DISABLE_UNCERTAIN:subId=" + esim.subId);
            }
        }

        // 비활성화 후 안정화 대기
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        // Re-read installed profiles, including disabled ones, after each attempt.
        int initialCount = esims.size();
        for (int attempt = 0; attempt < 2 && !esims.isEmpty(); attempt++) {
            for (EsimInfo esim : esims) deleteSubscription(esim);
            esims = findEsimProfiles(isub);
        }
        if (!esims.isEmpty()) {
            System.out.println("FAIL:ESIM_DELETE:remaining=" + esims.size());
            System.exit(1);
        }
        System.out.println("SUCCESS:ALL_ESIM_DELETED:" + initialCount + " profiles verified absent");
    }

    // ────────────────────────────────────────────
    // eSIM 정보 수집
    // ────────────────────────────────────────────

    private static class EsimInfo {
        int subId;
        String iccId;
        String carrier;
        int cardId;
        Object subInfo; // 원본 SubscriptionInfo 객체

        EsimInfo(int subId, String iccId, String carrier, int cardId, Object subInfo) {
            this.subId = subId;
            this.iccId = iccId;
            this.carrier = carrier;
            this.cardId = cardId;
            this.subInfo = subInfo;
        }
    }

    private static List<EsimInfo> findEsimProfiles(Object isub) throws Exception {
        Object result;
        try {
            result = isub.getClass().getMethod("getAvailableSubscriptionInfoList", String.class, String.class)
                .invoke(isub, "com.android.shell", null);
        } catch (NoSuchMethodException e) {
            result = isub.getClass().getMethod("getAvailableSubscriptionInfoList", String.class)
                .invoke(isub, "com.android.shell");
        }
        if (!(result instanceof List)) throw new IllegalStateException("Installed eSIM inventory unavailable");
        List<EsimInfo> esims = new ArrayList<>();
        for (Object sub : (List<?>) result) {
            if (!Boolean.TRUE.equals(sub.getClass().getMethod("isEmbedded").invoke(sub))) continue;
            int subId = getInt(sub, "getSubscriptionId", "getSubId");
            String iccId = getString(sub, "getIccId");
            int cardId = getInt(sub, "getCardId");
            if (subId < 0 || cardId < 0 || iccId == null || iccId.isEmpty())
                throw new IllegalStateException("Incomplete eSIM profile");
            esims.add(new EsimInfo(subId, iccId, null, cardId, sub));
        }
        return esims;
    }

    // ────────────────────────────────────────────
    // 비활성화 (3단계 폴백)
    // ────────────────────────────────────────────

    private static boolean disableSubscription(Object isub, int subId) {
        // 이미 비활성화 상태인지 확인
        boolean wasEnabled = isSubscriptionEnabled(isub, subId);
        if (!wasEnabled) {
            System.out.println("ALREADY_DISABLED:subId=" + subId);
            return true;
        }

        // 시도 1: setUiccApplicationsEnabled(false, subId)
        if (trySetUiccApplicationsEnabled(isub, subId)) {
            return true;
        }

        // 시도 2: setSubscriptionEnabled(subId, false) — Android 14+
        if (trySetSubscriptionEnabled(isub, subId)) {
            return true;
        }

        // 시도 3: ISub.setDisplayNameUsingSrc + inactive 마킹
        if (tryDeactivateViaISub(isub, subId)) {
            return true;
        }

        return false;
    }

    private static boolean trySetUiccApplicationsEnabled(Object isub, int subId) {
        try {
            Method m = isub.getClass().getMethod("setUiccApplicationsEnabled",
                boolean.class, int.class);
            m.invoke(isub, false, subId);
            System.out.println("ATTEMPT:setUiccApplicationsEnabled(false, " + subId + ")");
        } catch (Exception e) {
            System.err.println("setUiccApplicationsEnabled failed: " + e.getMessage());
            return false;
        }

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        boolean stillEnabled = isSubscriptionEnabled(isub, subId);
        if (!stillEnabled) {
            System.out.println("DISABLE_METHOD:setUiccApplicationsEnabled");
            return true;
        }
        return false;
    }

    private static boolean trySetSubscriptionEnabled(Object isub, int subId) {
        // Android 14+: setSubscriptionEnabled(int subId, boolean enable)
        try {
            Method m = isub.getClass().getMethod("setSubscriptionEnabled",
                int.class, boolean.class);
            Object result = m.invoke(isub, subId, false);
            System.out.println("ATTEMPT:setSubscriptionEnabled(" + subId + ", false) => " + result);
        } catch (Exception e) {
            System.err.println("setSubscriptionEnabled failed: " + e.getMessage());
            return false;
        }

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        boolean stillEnabled = isSubscriptionEnabled(isub, subId);
        if (!stillEnabled) {
            System.out.println("DISABLE_METHOD:setSubscriptionEnabled");
            return true;
        }
        return false;
    }

    private static boolean tryDeactivateViaISub(Object isub, int subId) {
        // 시도: setSubscriptionProperty로 sim_provisioning_status를 0으로 설정
        try {
            Method m = isub.getClass().getMethod("setSubscriptionProperty",
                int.class, String.class, String.class);
            m.invoke(isub, subId, "sim_provisioning_status", "0");
            System.out.println("ATTEMPT:setSubscriptionProperty(sim_provisioning_status=0)");
        } catch (Exception e) {
            System.err.println("setSubscriptionProperty failed: " + e.getMessage());
        }

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        boolean stillEnabled = isSubscriptionEnabled(isub, subId);
        if (!stillEnabled) {
            System.out.println("DISABLE_METHOD:setSubscriptionProperty");
            return true;
        }
        return false;
    }

    private static boolean isSubscriptionEnabled(Object isub, int subId) {
        // 방법 1: isSubscriptionEnabled
        try {
            Method m = isub.getClass().getMethod("isSubscriptionEnabled", int.class);
            return (boolean) m.invoke(isub, subId);
        } catch (Exception ignored) {}

        // 방법 2: areUiccApplicationsEnabled (Android 15+)
        try {
            Method m = isub.getClass().getMethod("areUiccApplicationsEnabled", int.class);
            return (boolean) m.invoke(isub, subId);
        } catch (Exception ignored) {}

        // 확인 실패 시 활성 상태로 가정
        return true;
    }

    // ────────────────────────────────────────────
    // eSIM 프로필 삭제 (3단계 폴백)
    // ────────────────────────────────────────────

    private static boolean deleteSubscription(EsimInfo esim) {
        // 시도 1: EuiccController.deleteSubscription
        if (tryDeleteViaEuiccController(esim)) {
            return true;
        }

        // 시도 2: EuiccCardManager (direct APDU-level deletion)
        if (tryDeleteViaEuiccCardManager(esim)) {
            return true;
        }

        return false;
    }

    private static boolean tryDeleteViaEuiccController(EsimInfo esim) {
        try {
            IBinder binder = getServiceBinder("econtroller");
            if (binder == null) {
                System.err.println("WARN:econtroller service not found");
                return false;
            }

            Object controller = null;
            try {
                Class<?> stubClass = Class.forName(
                    "com.android.internal.telephony.euicc.IEuiccController$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                controller = asInterface.invoke(null, binder);
            } catch (Exception e) {
                System.err.println("WARN:IEuiccController proxy failed: " + e.getMessage());
                return false;
            }

            if (controller == null) return false;

            // 실제 시그니처: deleteSubscription(int cardId, int subId, String pkg, PendingIntent)
            // PendingIntent에 null을 전달하여 시도
            boolean deleted = false;
            try {
                Method m = controller.getClass().getMethod("deleteSubscription",
                    int.class, int.class, String.class, PendingIntent.class);
                m.invoke(controller, esim.cardId, esim.subId, "com.android.shell",
                    (PendingIntent) null);
                deleted = true;
                System.out.println("DELETE_METHOD:IEuiccController.deleteSubscription(cardId,subId,pkg,null)");
            } catch (Exception e) {
                System.err.println("deleteSubscription(cardId,subId,pkg,PI) failed: " + e.getMessage());
            }

            if (!deleted) {
                // eraseSubscriptions: 전체 eSIM 삭제 (cardId, PendingIntent)
                try {
                    Method m = controller.getClass().getMethod("eraseSubscriptions",
                        int.class, PendingIntent.class);
                    m.invoke(controller, esim.cardId, (PendingIntent) null);
                    deleted = true;
                    System.out.println("DELETE_METHOD:IEuiccController.eraseSubscriptions(cardId,null)");
                } catch (Exception e) {
                    System.err.println("eraseSubscriptions failed: " + e.getMessage());
                }
            }

            if (!deleted) {
                // eraseSubscriptionsWithOptions(int cardId, int options, PendingIntent)
                // options: 0 = ERASE_ALL, 1 = RESET_DEFAULT_SMDP
                try {
                    Method m = controller.getClass().getMethod("eraseSubscriptionsWithOptions",
                        int.class, int.class, PendingIntent.class);
                    m.invoke(controller, esim.cardId, 0, (PendingIntent) null);
                    deleted = true;
                    System.out.println("DELETE_METHOD:IEuiccController.eraseSubscriptionsWithOptions");
                } catch (Exception e) {
                    System.err.println("eraseSubscriptionsWithOptions failed: " + e.getMessage());
                }
            }

            if (deleted) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                return true;
            }
        } catch (Exception e) {
            System.err.println("EuiccController approach failed: " + e.getMessage());
        }
        return false;
    }

    private static boolean tryDeleteViaEuiccCardManager(EsimInfo esim) {
        try {
            IBinder binder = getServiceBinder("euicc_card_controller");
            if (binder == null) {
                System.err.println("WARN:euicc_card_controller service not found");
                return false;
            }

            Object controller = null;
            try {
                Class<?> stubClass = Class.forName(
                    "com.android.internal.telephony.euicc.IEuiccCardController$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                controller = asInterface.invoke(null, binder);
            } catch (Exception e) {
                System.err.println("WARN:IEuiccCardController proxy failed: " + e.getMessage());
                return false;
            }

            if (controller == null) return false;

            // 실제 시그니처: deleteProfile(String cardId, String iccId, String pkg, IDeleteProfileCallback)
            // callback에 null 전달
            boolean deleted = false;
            try {
                Method m = controller.getClass().getMethod("deleteProfile",
                    String.class, String.class, String.class,
                    Class.forName("com.android.internal.telephony.euicc.IDeleteProfileCallback"));
                m.invoke(controller, String.valueOf(esim.cardId), esim.iccId,
                    "com.android.shell", null);
                deleted = true;
                System.out.println("DELETE_METHOD:IEuiccCardController.deleteProfile(cardId,iccId,pkg,cb)");
            } catch (Exception e) {
                System.err.println("deleteProfile(4) failed: " + e.getMessage());
            }

            if (!deleted) {
                // resetMemory: eUICC 전체 초기화 (모든 프로필 삭제)
                // resetMemory(String cardId, String pkg, int options, IResetMemoryCallback)
                try {
                    Method m = controller.getClass().getMethod("resetMemory",
                        String.class, String.class, int.class,
                        Class.forName("com.android.internal.telephony.euicc.IResetMemoryCallback"));
                    m.invoke(controller, String.valueOf(esim.cardId), "com.android.shell",
                        0, null);
                    deleted = true;
                    System.out.println("DELETE_METHOD:IEuiccCardController.resetMemory");
                } catch (Exception e) {
                    System.err.println("resetMemory failed: " + e.getMessage());
                }
            }

            if (deleted) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                return true;
            }
        } catch (Exception e) {
            System.err.println("EuiccCardManager approach failed: " + e.getMessage());
        }
        return false;
    }

    // ────────────────────────────────────────────
    // ISub 유틸리티
    // ────────────────────────────────────────────

    private static IBinder getServiceBinder(String name) throws Exception {
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Method getService = smClass.getMethod("getService", String.class);
        return (IBinder) getService.invoke(null, name);
    }

    private static Object getISubProxy() throws Exception {
        IBinder binder = getServiceBinder("isub");
        if (binder == null) throw new RuntimeException("isub service not found");
        Class<?> stubClass = Class.forName("com.android.internal.telephony.ISub$Stub");
        Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }

    @SuppressWarnings("unchecked")
    private static List<?> getActiveSubscriptions(Object isub) throws Exception {
        Object result = null;

        // Android 16 (API 36): getActiveSubscriptionInfoList(String, String, boolean)
        try {
            Method m = isub.getClass().getMethod("getActiveSubscriptionInfoList",
                String.class, String.class, boolean.class);
            result = m.invoke(isub, "com.android.shell", null, false);
        } catch (Exception ignored) {}

        // Android 13-15: getActiveSubscriptionInfoList(String, String)
        if (result == null) {
            try {
                Method m = isub.getClass().getMethod("getActiveSubscriptionInfoList",
                    String.class, String.class);
                result = m.invoke(isub, "com.android.shell", null);
            } catch (Exception ignored) {}
        }

        // Android 11-12: getActiveSubscriptionInfoList(String)
        if (result == null) {
            try {
                Method m = isub.getClass().getMethod("getActiveSubscriptionInfoList", String.class);
                result = m.invoke(isub, "com.android.shell");
            } catch (Exception ignored) {}
        }

        if (result == null) return Collections.emptyList();
        if (result instanceof List) return (List<?>) result;

        try {
            Method getList = result.getClass().getMethod("getList");
            return (List<?>) getList.invoke(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ────────────────────────────────────────────
    // 디버그: 서비스 메서드 목록 출력
    // ────────────────────────────────────────────

    private static void debugEuiccServices() throws Exception {
        // IEuiccController 메서드 목록
        String[] serviceNames = {"econtroller", "euicc_controller", "euicc_card_controller", "isub"};
        String[][] stubClasses = {
            {"econtroller", "com.android.internal.telephony.euicc.IEuiccController$Stub"},
            {"euicc_card_controller", "com.android.internal.telephony.euicc.IEuiccCardController$Stub"},
        };

        for (String svcName : serviceNames) {
            IBinder binder = getServiceBinder(svcName);
            System.out.println("SERVICE:" + svcName + "=" + (binder != null ? "FOUND" : "NOT_FOUND"));
        }

        for (String[] pair : stubClasses) {
            String svcName = pair[0];
            String className = pair[1];

            IBinder binder = getServiceBinder(svcName);
            if (binder == null) continue;

            try {
                Class<?> stubClass = Class.forName(className);
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Object proxy = asInterface.invoke(null, binder);

                System.out.println("\n=== " + className + " METHODS ===");
                for (Method m : proxy.getClass().getMethods()) {
                    String name = m.getName();
                    if (name.contains("delete") || name.contains("Delete")
                        || name.contains("remove") || name.contains("Remove")
                        || name.contains("disable") || name.contains("Disable")
                        || name.contains("erase") || name.contains("Erase")
                        || name.contains("switch") || name.contains("Switch")
                        || name.contains("reset") || name.contains("Reset")) {
                        Class<?>[] params = m.getParameterTypes();
                        StringBuilder sb = new StringBuilder();
                        sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                        sb.append(name).append("(");
                        for (int i = 0; i < params.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(params[i].getSimpleName());
                        }
                        sb.append(")");
                        System.out.println(sb.toString());
                    }
                }
            } catch (Exception e) {
                System.err.println("DEBUG:" + className + " failed: " + e.getMessage());
            }
        }

        // ISub의 관련 메서드도 출력
        try {
            Object isub = getISubProxy();
            System.out.println("\n=== ISub RELEVANT METHODS ===");
            for (Method m : isub.getClass().getMethods()) {
                String name = m.getName();
                if (name.contains("delete") || name.contains("Delete")
                    || name.contains("remove") || name.contains("Remove")
                    || name.contains("esim") || name.contains("Esim")
                    || name.contains("euicc") || name.contains("Euicc")
                    || name.contains("embedded") || name.contains("Embedded")
                    || name.contains("erase") || name.contains("Erase")) {
                    Class<?>[] params = m.getParameterTypes();
                    StringBuilder sb = new StringBuilder();
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(" ");
                    sb.append(name).append("(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(")");
                    System.out.println(sb.toString());
                }
            }
        } catch (Exception e) {
            System.err.println("ISub debug failed: " + e.getMessage());
        }
    }

    private static int getInt(Object obj, String... names) {
        for (String name : names) {
            try { return (int) obj.getClass().getMethod(name).invoke(obj); }
            catch (Exception ignored) {}
        }
        return -1;
    }

    private static String getString(Object obj, String... names) {
        for (String name : names) {
            try {
                Object val = obj.getClass().getMethod(name).invoke(obj);
                return val != null ? val.toString() : null;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
