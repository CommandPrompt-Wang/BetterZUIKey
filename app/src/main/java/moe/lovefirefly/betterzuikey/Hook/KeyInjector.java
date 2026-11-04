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
     * <p>顺序：修饰键 DOWN（Ctrl → Shift → Alt，每个先左后右）→ 主键 DOWN/UP →
     * 修饰键 UP（逆序）。每个事件都带上「当时已按下（含自己）」的修饰位，与物理键盘一致。
     *
     * <p>修饰键必须作为**真实的按键事件**发出去，而不是只把修饰位塞进主键的
     * {@code metaState}：只有后者的话，应用侧的「Ctrl 是否按住」状态机不会建立，
     * 依赖按住 Ctrl 的连续操作（远程桌面里的 Ctrl+拖动之类）就会断掉。
     *
     * @param map      映射目标；未设置或非法时直接返回
     * @param deviceId 物理 Meta 所在设备的 id（沿用可减少 InputDispatcher 的重复修饰合成）
     */
    public static void injectCombo(MetaKeyMap map, int deviceId) {
        injectChord(map, null, 0, 0, deviceId);
    }

    /**
     * 「按住 Win + 其它键」兜底合并专用。
     *
     * <p>例如 Win 映射为 `Ctrl(R)+Alt(R)+反引号`，按住 Win 再按 T（模块对 Win+T 没有
     * 特殊处理）时，发出的是一条和弦：`Ctrl(R)+Alt(R)+反引号+T`。
     *
     * <p>[extras] 是 Win 按住期间被扣押的外加修饰键，**严格按用户按下的先后顺序补在
     * 映射前缀之后**：用户先按 Ctrl(L) 再按 T，远端看到的顺序就是
     * `Ctrl(R)↓ Alt(R)↓ 反引号↓ Ctrl(L)↓ T↓`，而不是把 Ctrl(L) 提到最前面。
     *
     * @param extras          扣押的外加键（可为 null）；[HookContext.PendingExtra#stillDown]
     *                        为 true 的只发 DOWN，等它的物理 UP 来放行
     * @param triggerKeyCode  触发合并的键；≤0 表示没有（例如松 Win 时才补发）
     * @param triggerScanCode 触发键的扫描码
     */
    public static void injectMergedCombo(MetaKeyMap map,
                                         java.util.List<HookContext.PendingExtra> extras,
                                         int triggerKeyCode, int triggerScanCode,
                                         int deviceId) {
        injectChord(map, extras, triggerKeyCode, triggerScanCode, deviceId);
    }

    /** 单独补发一个被扣押的键（不并进前缀）；[stillDown] 为 false 时发一对 DOWN/UP。 */
    public static void injectExtraKey(int keyCode, int scanCode, int deviceId, boolean stillDown) {
        if (keyCode <= 0) return;
        injectKeyDown(keyCode, 0, deviceId, scanCode);
        if (!stillDown) injectKeyUp(keyCode, 0, deviceId, scanCode);
    }

    /**
     * 注入「修饰键 + 主键（+ 外加键 + 触发键）」这一条和弦。
     *
     * <p>顺序：映射目标的修饰键 DOWN（Ctrl → Shift → Alt，每个先左后右）→ 主键 DOWN →
     * 外加键（按用户按下的顺序）→ 触发键 DOWN/UP → 主键 UP → 映射修饰键 UP（逆序）。
     * 每个事件都带上「当时已按下（含自己）」的修饰位，与物理键盘一致。
     *
     * <p>修饰键必须作为**真实的按键事件**发出去，而不是只把修饰位塞进主键的
     * {@code metaState}：只有后者的话，应用侧的「Ctrl 是否按住」状态机不会建立，
     * 依赖按住 Ctrl 的连续操作（远程桌面里的 Ctrl+拖动之类）就会断掉。
     *
     * @param map      映射目标；未设置或非法时直接返回
     * @param deviceId 物理 Meta 所在设备的 id（沿用可减少 InputDispatcher 的重复修饰合成）
     */
    private static void injectChord(MetaKeyMap map,
                                    java.util.List<HookContext.PendingExtra> extras,
                                    int triggerKeyCode, int triggerScanCode,
                                    int deviceId) {
        if (map == null || !map.isSet()) return;
        final int keyCode = map.getKeyCode();
        if (keyCode <= 0) return;

        // 修饰键展开规则（左右、顺序）见 MetaKeyMap#modifierKeySequence
        final java.util.List<kotlin.Pair<Integer, Integer>> mods = map.modifierKeySequence();
        final java.util.List<kotlin.Pair<Integer, Integer>> pressed = new java.util.ArrayList<>(6);
        int acc = 0;
        for (int i = 0; i < mods.size(); i++) {
            kotlin.Pair<Integer, Integer> m = mods.get(i);
            // 用户按着同一个键（外加键里有）就不再补一个：同一个键按两遍会打架
            if (containsKeyCode(extras, m.getFirst())) continue;
            acc |= m.getSecond();
            injectKeyDown(m.getFirst(), acc, deviceId);
            pressed.add(m);
        }

        LogHelper.log(VerboseLevel.INFO, "INJECT combo: kc=", String.valueOf(keyCode),
                triggerKeyCode > 0 ? " +trigger=" + triggerKeyCode : "",
                " extras=", String.valueOf(extras == null ? 0 : extras.size()),
                " ctrl=", String.valueOf(map.getCtrl()),
                " shift=", String.valueOf(map.getShift()),
                " alt=", String.valueOf(map.getAlt()));

        // 主键带上录制时的原始 scanCode：ZUI 的顶行虚拟键（500/501/503/504/507…）
        // 认的正是它自己那套 scanCode，丢掉 ZUI 就不认（见 FnKeyManager 的注释）。
        final int scanCode = map.getScanCode();
        injectKeyDown(keyCode, acc, deviceId, scanCode);

        // 外加键：按用户按下的顺序补在前缀之后
        if (extras != null) {
            for (int i = 0; i < extras.size(); i++) {
                HookContext.PendingExtra e = extras.get(i);
                int bit = metaBitOf(e.keyCode);
                acc |= bit;
                injectKeyDown(e.keyCode, acc, deviceId, e.scanCode);
                if (!e.stillDown) {
                    // 用户在合并发生前就松手了：在它的位置上发一对 DOWN/UP
                    injectKeyUp(e.keyCode, acc, deviceId, e.scanCode);
                    acc &= ~bit;
                }
                // stillDown：留着不抬，等用户真的松手时由物理 UP 放行
            }
        }

        // 触发键（刚按下的那个键）自己是一对 DOWN/UP
        if (triggerKeyCode > 0 && triggerKeyCode != keyCode) {
            injectKeyDown(triggerKeyCode, acc, deviceId, triggerScanCode);
            injectKeyUp(triggerKeyCode, acc, deviceId, triggerScanCode);
        }

        injectKeyUp(keyCode, acc, deviceId, scanCode);
        for (int i = pressed.size() - 1; i >= 0; i--) {
            kotlin.Pair<Integer, Integer> m = pressed.get(i);
            injectKeyUp(m.getFirst(), acc, deviceId);
            acc &= ~m.getSecond();
        }
    }

    private static boolean containsKeyCode(java.util.List<HookContext.PendingExtra> extras,
                                           int keyCode) {
        if (extras == null) return false;
        for (int i = 0; i < extras.size(); i++) {
            if (extras.get(i).keyCode == keyCode) return true;
        }
        return false;
    }

    /** 修饰键对应的 metaState 位（侧别精确）；非修饰键返回 0。 */
    private static int metaBitOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT: return KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT: return KeyEvent.META_ALT_RIGHT_ON;
            default: return 0;
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
