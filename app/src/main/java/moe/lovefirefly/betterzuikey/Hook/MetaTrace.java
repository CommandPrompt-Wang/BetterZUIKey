package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Unified Meta/Win key trace — filter logcat with {@code MetaTrace}.
 * <p>
 * While {@link #TRACE_ONLY} is true, Meta routing is observe-only (no consume /
 * inject / Start Menu / long-press timers). Set to false after collecting logs.
 */
public final class MetaTrace {

    /** Temporary: observe Meta path without changing behavior. */
    public static final boolean TRACE_ONLY = false;

    private MetaTrace() {}

    public static boolean isTraceOnly() {
        return TRACE_ONLY;
    }

    public static void event(String layer, KeyEvent event, HookContext ctx) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        LogHelper.log(VerboseLevel.DEBUG,
                "MetaTrace ", layer,
                down ? " D" : " U",
                " kc=", String.valueOf(event.getKeyCode()),
                " sc=", String.valueOf(event.getScanCode()),
                " dev=", String.valueOf(event.getDeviceId()),
                " r=", String.valueOf(event.getRepeatCount()),
                " inj=", ctx != null && ctx.isInjecting() ? "1" : "0",
                " zui=", event.getScanCode() == HookContext.ZUI_META_SCAN_CODE ? "1" : "0",
                sessionSuffix(ctx));
    }

    public static void session(String layer, String action, HookContext ctx) {
        LogHelper.log(VerboseLevel.DEBUG,
                "MetaTrace ", layer, " session ", action, sessionSuffix(ctx));
    }

    public static void decision(String layer, String action, String detail) {
        LogHelper.log(VerboseLevel.DEBUG,
                "MetaTrace ", layer, " → ", action,
                detail != null && !detail.isEmpty() ? (" (" + detail + ")") : "");
    }

    public static void decision(String layer, String action, String... parts) {
        if (parts == null || parts.length == 0) {
            decision(layer, action, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i]);
        }
        decision(layer, action, sb.toString());
    }

    public static void hookResult(String layer, boolean consumed) {
        LogHelper.log(VerboseLevel.DEBUG,
                "MetaTrace ", layer, " hookResult consumed=", consumed ? "1" : "0");
    }

    private static String sessionSuffix(HookContext ctx) {
        if (ctx == null) return "";
        HookContext.MetaKeySession s = ctx.metaSession;
        if (s == null) return "";
        long elapsed = s.active && s.downTimeMs > 0
                ? System.currentTimeMillis() - s.downTimeMs : -1;
        return " | sess active=" + (s.active ? "1" : "0")
                + " kc=" + s.keyCode
                + " sc=" + s.scanCode
                + " upH=" + (s.upHandled ? "1" : "0")
                + " long=" + (s.longFired ? "1" : "0")
                + " ms=" + elapsed;
    }
}
