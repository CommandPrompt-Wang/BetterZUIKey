package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;
import android.view.InputEvent;

import java.io.PrintWriter;
import java.io.StringWriter;

import de.robv.android.xposed.XposedHelpers;

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
        } catch (Throwable ignored) { }
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
            Object policy = XposedHelpers.getObjectField(mKscInstance, "mPolicy");
            XposedHelpers.callMethod(policy, "injectKeyEvent", keyCode);
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
        if (keyCode <= 0) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode,
                    0, metaState, deviceId, 0, 0);
            Object im = XposedHelpers.callStaticMethod(
                    android.hardware.input.InputManager.class, "getInstance");
            XposedHelpers.callMethod(im, "injectInputEvent", (InputEvent) ev, 0);
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.ERROR, "injectKeyDown failed (keyCode=",
                    String.valueOf(keyCode), " deviceId=", String.valueOf(deviceId),
                    "):", t.getMessage());
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
        if (keyCode <= 0) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode,
                    0, metaState, deviceId, 0, 0);
            Object im = XposedHelpers.callStaticMethod(
                    android.hardware.input.InputManager.class, "getInstance");
            XposedHelpers.callMethod(im, "injectInputEvent", (InputEvent) ev, 0);
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
                } catch (Exception ignored) { }
            });
        } catch (Throwable ignored) { }
    }
}
