package moe.lovefirefly.betterzuikey.Utils;

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

    private static final String TAG = "BetterZUIKey";

    public static void log(VerboseLevel vlevel, String ...args) {
        String msg = "[" + vlevel.getLabel() + "] " + String.join(" ", args);

        if (vlevel.ordinal() > currentLevel.ordinal()) {
            return;
        }
        switch (vlevel) {
            case ERROR:   android.util.Log.e(TAG, msg); break;
            case WARNING: android.util.Log.w(TAG, msg); break;
            case DEBUG:   android.util.Log.d(TAG, msg); break;
            default:      android.util.Log.i(TAG, msg); break;
        }
    }

}
