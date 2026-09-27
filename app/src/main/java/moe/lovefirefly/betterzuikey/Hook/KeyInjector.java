package moe.lovefirefly.betterzuikey.Hook;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.InputEvent;

import java.io.PrintWriter;
import java.io.StringWriter;



import moe.lovefirefly.betterzuikey.MetaKeyMap;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;

/**
 * Static utility methods for key injection, KeyEvent manipulation, and UI helpers.
 * All methods are self-contained and thread-safe.
 */
public final class KeyInjector {

    private KeyInjector() { /* utility class */ }

    // ----------------------------------------------------------------
    //  Debug / diagnostics
    // ----------------------------------------------------------------

    /** Write a debug value to SystemProperties (no-op on failure). Read via: adb shell getprop */
    public static void debugProp(String key, String value) {
        try {
            Class.forName("android.os.SystemProperties")
                    .getMethod("set", String.class, String.class)
                    .invoke(null, key, value);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "debugProp failed:", t.getMessage());
        }
    }

    /** Convert a Throwable stack trace to String. */
    public static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** Safe keyCode-to-string conversion. */
    public static String keyCodeToString(int code) {
        try {
            return KeyEvent.keyCodeToString(code);
        } catch (Exception e) {
            return String.valueOf(code);
        }
    }

    // ----------------------------------------------------------------
    //  KeyEvent mutation (reflection-based, avoids extra modifier events)
    // ----------------------------------------------------------------

    /**
     * Strip modifier bits from a KeyEvent's metaState via reflection.
     * Unlike injectInputEvent, this mutates the original physical event in-place,
     * avoiding extra modifier events and timing issues.
     */
    public static void stripMetaState(KeyEvent event, int mask) {
        try {
            java.lang.reflect.Field f = KeyEvent.class.getDeclaredField("mMetaState");
            f.setAccessible(true);
            f.setInt(event, event.getMetaState() & ~mask);
        } catch (Exception e) {
            LogHelper.log(VerboseLevel.ERROR, "stripMetaState failed:", e.getMessage());
        }
    }

    /**
     * Exact modifier-key match: returns true only when the event's Meta / Shift /
     * Ctrl / Alt state matches every parameter. Use this instead of bare
     * {@code event.isMetaPressed()} to avoid accidentally catching combos that
     * include extra modifiers (e.g. Win+Alt+4 being misrouted as Win+4).
     */
    public static boolean modifiersMatch(KeyEvent event, boolean meta, boolean shift,
                                        boolean ctrl, boolean alt) {
        return event.isMetaPressed() == meta
            && event.isShiftPressed() == shift
            && event.isCtrlPressed() == ctrl
            && event.isAltPressed() == alt;
    }

    /** True for Ctrl / Shift / Alt / Meta / Caps / Num / Fn lock keys. */
    public static boolean isModifierKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_FUNCTION:
                return true;
            default:
                return false;
        }
    }

    public static boolean isCtrlKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT;
    }

    public static boolean isShiftKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

    public static boolean isAltKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ALT_LEFT
                || keyCode == KeyEvent.KEYCODE_ALT_RIGHT;
    }

    // ----------------------------------------------------------------
    //  Key injection (InputManager.injectInputEvent)
    // ----------------------------------------------------------------

    /**
     * Inject a combined DOWN+UP key event via KeyboardZuiKeyInputPolicy.injectKeyEvent.
     * Requires a captured mKscInstance.
     */
    public static void injectKey(int keyCode, Object mKscInstance) {
        if (keyCode <= 0 || mKscInstance == null) return;
        try {
            Object policy = HookCompat.getObjectField(mKscInstance, "mPolicy");
            HookCompat.callMethod(policy, "injectKeyEvent", keyCode);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "injectKey failed (keyCode=",
                    String.valueOf(keyCode), "):", t.getMessage());
        }
    }

    /** Inject a clean key DOWN event (metaState=0, deviceId=0). */
    public static void injectKeyDown(int keyCode) {
        injectKeyDown(keyCode, 0, 0);
    }

    /**
     * Inject a key DOWN event with given metaState and deviceId.
     * Using the real keyboard deviceId prevents Android InputDispatcher from
     * synthesizing duplicate modifier events (e.g. extra Alt UP) when metaState
     * carries modifier bits.
     */
    public static void injectKeyDown(int keyCode, int metaState, int deviceId) {
        injectKeyDown(keyCode, metaState, deviceId, 0);
    }

    /**
     * 把注入事件标记为真实的键盘来源。
     * <p>
     * {@code new KeyEvent(...)} 默认 source=0，而 {@code 0} 并不是
     * {@code SOURCE_KEYBOARD}，{@code KeyEvent.isKeyboardSource()} 会返回 false，
     * 许多应用（含远程桌面类）会因此直接丢弃事件；displayId 也需指向默认屏幕。
     */
    private static void markAsKeyboard(KeyEvent ev) {
        try {
            ev.setSource(InputDevice.SOURCE_KEYBOARD);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "setSource failed:", t.getMessage());
        }
        try {
            HookCompat.callMethod(ev, "setDisplayId", 0);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "setDisplayId failed:", t.getMessage());
        }
    }

    /** Inject a key DOWN event with explicit scanCode (for ZUI custom keys). */
    public static void injectKeyDown(int keyCode, int metaState, int deviceId, int scanCode) {
        if (keyCode <= 0) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode,
                    0, metaState, deviceId, scanCode, 0);
            markAsKeyboard(ev);
            LogHelper.log(VerboseLevel.INFO, "INJECT",
                    " DOWN kc=", String.valueOf(keyCode),
                    " meta=0x", Integer.toHexString(metaState),
                    " dev=", String.valueOf(deviceId),
                    " sc=", String.valueOf(scanCode),
                    " flags=0x", Integer.toHexString(ev.getFlags()));
            Object im = HookCompat.callStaticMethod(
                    android.hardware.input.InputManager.class, "getInstance");
            HookCompat.callMethod(im, "injectInputEvent", (InputEvent) ev, 0);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "injectKeyDown failed (keyCode=",
                    String.valueOf(keyCode), " deviceId=", String.valueOf(deviceId),
                    "):", t.getMessage());
        }
    }

    /**
     * 放行模式（{@code metaSingle = OFF}）专用：注入一对合成的 Meta DOWN/UP，把
     * 「单按 Win」交给前台应用。
     * <p>
     * 必须尽量还原物理事件，否则应用会丢弃它：
     * <ul>
     *   <li>{@code source} 必须是 {@code SOURCE_KEYBOARD}——默认 0 不是键盘来源，
     *       {@code KeyEvent.isKeyboardSource()} 为 false，大量应用会直接忽略；</li>
     *   <li>{@code scanCode} 沿用物理值，Chromium 等要靠它映射 {@code event.code}
     *       （如 {@code MetaLeft}）；</li>
     *   <li>DOWN 的 metaState 与物理一致（{@code META_META_ON | ..._LEFT_ON}）。</li>
     * </ul>
     * deviceId 无法保留（InputDispatcher 会把注入事件的 deviceId 归一化为 -1），
     * 这也正好成为 {@link HookContext#isSyntheticMetaTap} 的判定依据。
     */
    public static void injectMetaTap(KeyEvent src) {
        if (src == null) return;
        int keyCode = src.getKeyCode();
        int deviceId = src.getDeviceId();
        int scanCode = src.getScanCode();
        int downMeta = KeyEvent.META_META_ON
                | (keyCode == KeyEvent.KEYCODE_META_RIGHT
                        ? KeyEvent.META_META_RIGHT_ON
                        : KeyEvent.META_META_LEFT_ON);
        injectKeyDown(keyCode, downMeta, deviceId, scanCode);
        injectKeyUp(keyCode, 0, deviceId, scanCode);
    }

    /**
     * 「映射到…」专用：注入一个组合键。
     *
     * <p>顺序：修饰键 DOWN（Ctrl → Shift → Alt）→ 主键 DOWN/UP → 修饰键 UP（逆序）。
     * 每个事件都带上「当时已按下（含自己）」的修饰位，与物理键盘一致。
     *
     * <p>修饰键必须作为**真实的按键事件**发出去，而不是只把修饰位塞进主键的
     * {@code metaState}：只有后者的话，应用侧的「Ctrl 是否按住」状态机不会建立，
     * 依赖按住 Ctrl 的连续操作（远程桌面里的 Ctrl+拖动之类）就会断掉。
     *
     * @param map      映射目标；未设置或非法时直接返回
     * @param deviceId 物理 Meta 所在设备的 id（沿用可减少 InputDispatcher 的重复修饰合成）
     */
    public static void injectCombo(MetaKeyMap map, int deviceId) {
        if (map == null || !map.isSet()) return;
        final int keyCode = map.getKeyCode();
        if (keyCode <= 0) return;

        final int[] mods = new int[3];
        int n = 0;
        if (map.getCtrl()) mods[n++] = KeyEvent.KEYCODE_CTRL_LEFT;
        if (map.getShift()) mods[n++] = KeyEvent.KEYCODE_SHIFT_LEFT;
        if (map.getAlt()) mods[n++] = KeyEvent.KEYCODE_ALT_LEFT;

        LogHelper.log(VerboseLevel.INFO, "INJECT combo: kc=", String.valueOf(keyCode),
                " ctrl=", String.valueOf(map.getCtrl()),
                " shift=", String.valueOf(map.getShift()),
                " alt=", String.valueOf(map.getAlt()));

        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc |= metaBitOf(mods[i]);
            injectKeyDown(mods[i], acc, deviceId);
        }
        injectKeyDown(keyCode, acc, deviceId);
        injectKeyUp(keyCode, acc, deviceId);
        for (int i = n - 1; i >= 0; i--) {
            injectKeyUp(mods[i], acc, deviceId);
            acc &= ~metaBitOf(mods[i]);
        }
    }

    private static int metaBitOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return KeyEvent.META_CTRL_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return KeyEvent.META_SHIFT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return KeyEvent.META_ALT_ON;
            default:
                return 0;
        }
    }

    /** Inject a clean key UP event (metaState=0, deviceId=0). */
    public static void injectKeyUp(int keyCode) {
        injectKeyUp(keyCode, 0, 0);
    }

    /**
     * Inject a key UP event with given metaState and deviceId.
     * Using the real keyboard deviceId prevents Android InputDispatcher from
     * synthesizing duplicate modifier events.
     */
    public static void injectKeyUp(int keyCode, int metaState, int deviceId) {
        injectKeyUp(keyCode, metaState, deviceId, 0);
    }

    /** Inject a key UP event with explicit scanCode (for ZUI custom keys). */
    public static void injectKeyUp(int keyCode, int metaState, int deviceId, int scanCode) {
        if (keyCode <= 0) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode,
                    0, metaState, deviceId, scanCode, 0);
            markAsKeyboard(ev);
            LogHelper.log(VerboseLevel.INFO, "INJECT",
                    " UP   kc=", String.valueOf(keyCode),
                    " meta=0x", Integer.toHexString(metaState),
                    " dev=", String.valueOf(deviceId),
                    " sc=", String.valueOf(scanCode));
            Object im = HookCompat.callStaticMethod(
                    android.hardware.input.InputManager.class, "getInstance");
            HookCompat.callMethod(im, "injectInputEvent", (InputEvent) ev, 0);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "injectKeyUp failed (keyCode=",
                    String.valueOf(keyCode), " deviceId=", String.valueOf(deviceId),
                    "):", t.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  Toast (main-thread)
    // ----------------------------------------------------------------

    private static android.widget.Toast sFnToast = null;

    /** Show a short Toast on the main thread. Safe to call from any thread. */
    public static void showToast(String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    if (sFnToast != null) sFnToast.cancel();
                    Object at = Class.forName("android.app.ActivityThread")
                            .getMethod("currentActivityThread").invoke(null);
                    android.content.Context ctx = (android.content.Context)
                            at.getClass().getMethod("getSystemContext").invoke(at);
                    sFnToast = android.widget.Toast.makeText(ctx, msg,
                            android.widget.Toast.LENGTH_SHORT);
                    sFnToast.show();
                } catch (Exception e) {
                    LogHelper.log(VerboseLevel.DEBUG, "showToast inner failed:", e.getMessage());
                }
            });
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "showToast failed:", t.getMessage());
        }
    }
}
