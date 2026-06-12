package moe.lovefirefly.betterzuikey.Utils;

import moe.lovefirefly.betterzuikey.BuildConfig;

public class LogHelper {
    public enum VerboseLevel {
        SILENT("SILENT"),
        ERROR("ERROR"),
        WARNING("WARNING"),
        INFO("INFO"),
        DEBUG("DEBUG");

        private final String label;

        VerboseLevel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static volatile VerboseLevel currentLevel = VerboseLevel.INFO;
    private static long sConfigLastModified = 0;
    private static long sConfigCheckTime = 0;
    private static final String CONFIG_PATH =
        "/data/data/moe.lovefirefly.betterzuikey/config.json";

    private static final String TAG = "BetterZUIKey";

    public static void log(VerboseLevel vlevel, String ...args) {
        refreshLevel();
        String msg = "[" + vlevel.getLabel() + "] " + String.join(" ", args);

        // 编译期开关：无视优先级，输出 [TMP] 毫秒时间戳行到 logcat
        if (BuildConfig.DEBUG_TMP_LOG) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US);
            android.util.Log.i(TAG, "[TMP] " + sdf.format(new java.util.Date()) + " " + msg);
        }

        if (vlevel.ordinal() > currentLevel.ordinal()) {
            return;
        }
        try {
            // Xposed 进程：用 XposedBridge.log
            de.robv.android.xposed.XposedBridge.log(TAG + " " + msg);
        } catch (NoClassDefFoundError ignored) {
            // App 进程：XposedBridge 不可用，回退 android.util.Log
            switch (vlevel) {
                case ERROR:   android.util.Log.e(TAG, msg); break;
                case WARNING: android.util.Log.w(TAG, msg); break;
                case DEBUG:   android.util.Log.d(TAG, msg); break;
                default:      android.util.Log.i(TAG, msg); break;
            }
        }
    }

    /** 定期检查配置文件是否更新，自动刷新日志级别 */
    private static void refreshLevel() {
        long now = System.currentTimeMillis();
        if (now - sConfigCheckTime < 2000) return;
        sConfigCheckTime = now;
        java.io.BufferedReader br = null;
        try {
            java.io.File f = new java.io.File(CONFIG_PATH);
            long mod = f.lastModified();
            if (mod == sConfigLastModified) return;
            sConfigLastModified = mod;
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String json = sb.toString();
            int idx = json.indexOf("\"verboseLevel\"");
            if (idx > 0) {
                int colon = json.indexOf(":", idx);
                int start = json.indexOf("\"", colon) + 1;
                int end = json.indexOf("\"", start);
                if (start > 0 && end > start) {
                    String level = json.substring(start, end);
                    for (VerboseLevel vl : VerboseLevel.values()) {
                        if (vl.getLabel().equals(level)) {
                            currentLevel = vl;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) { }
        }
    }
}
