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
            Object result = getTasks.invoke(am, 100, 0, 0);

            List<?> tasks;
            if (result instanceof List) {
                tasks = (List<?>) result;
            } else {
                // ParceledListSlice → getList()
                Method getList = result.getClass().getMethod("getList");
                tasks = (List<?>) getList.invoke(result);
            }

            if (tasks == null || tasks.isEmpty()) {
                System.out.println("SUCCESS: No recent tasks to remove");
                return;
            }

            int removed = 0;
            for (Object task : tasks) {
                int taskId = task.getClass().getField("persistentId").getInt(task);
                if (taskId <= 0) continue;

                try {
                    Method removeTask = am.getClass().getMethod("removeTask", int.class);
                    removeTask.invoke(am, taskId);
                    removed++;
                } catch (Exception e) {
                    try {
                        Method removeTask2 = am.getClass().getMethod(
                            "removeTask", int.class, int.class);
                        removeTask2.invoke(am, taskId, 0);
                        removed++;
                    } catch (Exception e2) {
                        System.err.println("SKIP: taskId=" + taskId + " " + e2.getMessage());
                    }
                }
            }

            System.out.println("SUCCESS: Removed " + removed + " recent tasks");
        } catch (Exception e) {
            System.err.println("FAIL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
