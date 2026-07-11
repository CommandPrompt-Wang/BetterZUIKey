package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Passthrough debug â€?filter logcat with {@code PassthroughTrace}.
 * <p>
 * Traces selected keys through L0/L1/L2 to find where events are consumed.
 * Set {@link #ENABLED} false when done debugging.
 */
public final class PassthroughTrace {

    /** Master switch for passthrough layer tracing. */
    public static final boolean ENABLED = true;

    private PassthroughTrace() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean shouldTrace(KeyEvent event) {
        if (!ENABLED || event == null) return false;
        return isTrackedKeyCode(event.getKeyCode());
    }

    public static boolean isTrackedKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_Z:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return true;
            default:
                return false;
        }
    }

    public static void in(String layer, KeyEvent event, HookContext ctx) {
        LogHelper.log(VerboseLevel.DEBUG, line(layer, "IN", event, ctx, null, false));
    }

    public static void out(String layer, KeyEvent event, HookCompat.HookParam param) {
        boolean consumed = param != null && param.isReturnEarly();
        LogHelper.log(VerboseLevel.DEBUG,
                line(layer, "OUT", event, null, param, consumed));
    }

    public static void note(String layer, String action, KeyEvent event) {
        if (!ENABLED || event == null || !shouldTrace(event)) return;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        LogHelper.log(VerboseLevel.DEBUG,
                "PassthroughTrace ", layer, " * ", action,
                " kc=", String.valueOf(event.getKeyCode()),
                down ? " D" : " U",
                " sc=", String.valueOf(event.getScanCode()));
    }

    public static void noteKey(String layer, String action, int keyCode) {
        if (!ENABLED || !isTrackedKeyCode(keyCode)) return;
        LogHelper.log(VerboseLevel.DEBUG,
                "PassthroughTrace ", layer, " * ", action, " kc=", String.valueOf(keyCode));
    }

    /** Layer note without a KeyEvent (voice timer, policy calls). */
    public static void noteMsg(String layer, String action) {
        if (!ENABLED) return;
        LogHelper.log(VerboseLevel.DEBUG,
                "PassthroughTrace ", layer, " * ", action);
    }

    private static String line(String layer, String phase, KeyEvent event,
                               HookContext ctx, HookCompat.HookParam param,
                               boolean consumed) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        StringBuilder sb = new StringBuilder();
        sb.append("PassthroughTrace ").append(layer).append(' ').append(phase);
        sb.append(down ? " D" : " U");
        sb.append(" kc=").append(event.getKeyCode());
        sb.append(" sc=").append(event.getScanCode());
        sb.append(" dev=").append(event.getDeviceId());
        sb.append(" r=").append(event.getRepeatCount());
        sb.append(" meta=").append(event.isMetaPressed() ? "1" : "0");
        sb.append(" ctrl=").append(event.isCtrlPressed() ? "1" : "0");
        sb.append(" shift=").append(event.isShiftPressed() ? "1" : "0");
        sb.append(" alt=").append(event.isAltPressed() ? "1" : "0");
        sb.append(" flags=0x").append(Integer.toHexString(event.getFlags()));
        sb.append(" src=0x").append(Integer.toHexString(event.getSource()));
        if (ctx != null) {
            sb.append(" inj=").append(ctx.isInjecting() ? "1" : "0");
        }
        if ("OUT".equals(phase)) {
            sb.append(" consumed=").append(consumed ? "1" : "0");
        }
        return sb.toString();
    }
}
