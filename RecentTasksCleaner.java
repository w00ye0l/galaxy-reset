import java.lang.reflect.Method;
import java.util.List;

/**
 * 비루트 환경에서 최근 앱 목록을 전부 제거하는 헬퍼.
 *
 * 사용법: app_process /system/bin RecentTasksCleaner
 */
public class RecentTasksCleaner {
    public static void main(String[] args) {
        try {
            Class<?> amClass = Class.forName("android.app.ActivityManagerNative");
            Method getDefault = amClass.getMethod("getDefault");
            Object am = getDefault.invoke(null);

            // getRecentTasks → ParceledListSlice를 반환하므로 getList()로 변환
            Method getTasks = am.getClass().getMethod(
                "getRecentTasks", int.class, int.class, int.class);
            List<?> tasks = recentTasks(getTasks, am);
            for (int attempt = 0; attempt < 2 && !tasks.isEmpty(); attempt++) {
                for (Object task : tasks) {
                    int taskId = task.getClass().getField("persistentId").getInt(task);
                    if (taskId <= 0) continue;
                    try {
                        am.getClass().getMethod("removeTask", int.class).invoke(am, taskId);
                    } catch (Exception e) {
                        try {
                            am.getClass().getMethod("removeTask", int.class, int.class).invoke(am, taskId, 0);
                        } catch (Exception fallback) {
                            System.err.println("SKIP: taskId=" + taskId + " " + fallback.getClass().getSimpleName());
                        }
                    }
                }
                tasks = recentTasks(getTasks, am);
            }
            if (!tasks.isEmpty()) {
                System.err.println("FAIL:RECENTS_REMAIN:" + tasks.size());
                System.exit(1);
            }
            System.out.println("SUCCESS:RECENTS_EMPTY");
        } catch (Exception e) {
            System.err.println("FAIL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    private static List<?> recentTasks(Method query, Object manager) throws Exception {
        Object result = query.invoke(manager, 100, 0, 0);
        if (result != null && !(result instanceof List)) result = result.getClass().getMethod("getList").invoke(result);
        if (!(result instanceof List)) throw new IllegalStateException("Recent task inventory unavailable");
        return (List<?>) result;
    }

}
