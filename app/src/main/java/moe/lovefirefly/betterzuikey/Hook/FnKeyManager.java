package moe.lovefirefly.betterzuikey.Hook;

import android.view.KeyEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import moe.lovefirefly.betterzuikey.Config.Config;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import static moe.lovefirefly.betterzuikey.Utils.LogHelper.VerboseLevel;
import static moe.lovefirefly.betterzuikey.Hook.KeyInjector.*;

/**
 * Manages all virtual-Fn-key state and processing for the L0 hook layer.
 *
 * Responsibilities:
 * <ul>
 *   <li>Win+Tab / Win+P / generic Win+combo UP cleanup (pre-shortcut section)</li>
 *   <li>Win+&#96; FnLock toggle and Meta UP consumption</li>
 *   <li>Virtual Fn key mapping (F1–F12) and restore-original-key path</li>
 *   <li>Fn UP event injection for mapped/restored keys</li>
 *   <li>Keyboard profile detection and keyCode→F-key lookup</li>
 * </ul>
 */
public class FnKeyManager {

    private final Config cfg;
    private final ConfigIPCManager configIPC;

    // ---- Fn mapping state ----

    /** keyCodes whose DOWN was intercepted by Fn mapping, used to intercept matching UP */
    private final Set<Integer> mFnDownKeys = ConcurrentHashMap.newKeySet();

    /** physicalKeyCode → fKeyCode for Fn-mapped keys, used to inject UP */
    private final Map<Integer, Integer> mFnDownKeyMap = new ConcurrentHashMap<>();

    /** physicalKeyCode → metaState for Fn-mapped DOWN, used to inject UP with same modifiers */
    private final Map<Integer, Integer> mFnDownMetaMap = new ConcurrentHashMap<>();

    /** keyCodes whose clean DOWN was injected for restore; physical UP triggers clean UP */
    private final Set<Integer> mRestoreDownPending = ConcurrentHashMap.newKeySet();

    /** physicalKeyCode → metaState for restore-injected keys, used to inject UP with same modifiers */
    private final Map<Integer, Integer> mRestoreDownMeta = new ConcurrentHashMap<>();

    /** keyCode → F-keyCode mapping cache (use keyCode because ZUI overwrites scanCode) */
    private volatile Map<Integer, Integer> mFnKeyCodeMap = null;

    /** Profile key used to build the current mFnKeyCodeMap (for cache invalidation) */
    private volatile String mFnMapProfileKey = null;

    /** Cached set of keyboard device IDs that match known Fn profiles */
    private final Set<Integer> mFnKeyboardDeviceIds = ConcurrentHashMap.newKeySet();

    // ---- Combo cleanup state (set by MainHook during shortcut DOWN, consumed here on UP) ----

    /** Consume next Meta UP after Win+&#96; to prevent Start menu */
    private boolean mConsumeNextMetaUp = false;

    /** Consume next Meta UP after MetaAction.NONE consumed the DOWN (prevents unbalanced UP) */
    private boolean mConsumeMetaUpForNone = false;

    /** Win+Tab OFF mode: when true, next Tab UP with Meta must be consumed */
    private boolean mWinTabOffActive = false;

    /** Win+Tab BLOCK mode: when true, next Tab UP with Meta must be consumed */
    private boolean mWinTabBlockActive = false;

    /** Win+P OFF mode: when true, next P UP must be blocked and clean UP injected */
    private boolean mWinPOffActive = false;

    /** Generic Win+key BLOCK: if non-zero, consume next UP of this keyCode with Meta */
    private int mPendingBlockedWinComboUp = 0;

    // ----------------------------------------------------------------
    //  Construction
    // ----------------------------------------------------------------

    public FnKeyManager(Config cfg, ConfigIPCManager configIPC) {
        this.cfg = cfg;
        this.configIPC = configIPC;
    }

    // ----------------------------------------------------------------
    //  State setters (called by MainHook during shortcut DOWN handling)
    // ----------------------------------------------------------------

    public void setWinTabOffActive(boolean v)   { mWinTabOffActive = v; }
    public void setWinTabBlockActive(boolean v) { mWinTabBlockActive = v; }
    public void setWinPOffActive(boolean v)     { mWinPOffActive = v; }
    public void setPendingBlockedWinComboUp(int keyCode) { mPendingBlockedWinComboUp = keyCode; }
    public void setConsumeMetaUpForNone()       { mConsumeMetaUpForNone = true; }

    // ----------------------------------------------------------------
    //  L0 pre-shortcut UP cleanup (Win+Tab, Win+P, generic Win+combo UP)
    //  Called BEFORE all shortcut checks in L0.
    // ----------------------------------------------------------------

    /**
     * Consume UP events for Win+Tab OFF/BLOCK, Win+P OFF, and generic blocked Win+combo.
     * Must be called before any shortcut DOWN checks and before Fn keyboard processing.
     *
     * @return true if the event was consumed (caller must return immediately)
     */
    public boolean consumeComboUp(int keyCode, boolean down,
                                   KeyEvent event, XC_MethodHook.MethodHookParam param) {
        // Win+Tab OFF/BLOCK mode — consume Tab UP (prevent leak)
        // Must come BEFORE Fn keyboard processing which also handles Tab UP
        if (keyCode == KeyEvent.KEYCODE_TAB && !down && event.isMetaPressed()) {
            if (mWinTabOffActive) {
                mWinTabOffActive = false;
                param.setResult(true);
                debugProp("debug.bzuikey.l0.wintab", "ACTION: OFF UP consumed");
                LogHelper.log(VerboseLevel.INFO, "L0: Win+Tab OFF UP → consumed");
                return true;
            }
            if (mWinTabBlockActive) {
                mWinTabBlockActive = false;
                param.setResult(true);
                debugProp("debug.bzuikey.l0.wintab", "ACTION: BLOCK UP consumed");
                LogHelper.log(VerboseLevel.INFO, "L0: Win+Tab BLOCK UP → consumed");
                return true;
            }
        }

        // Win+P OFF mode — block physical P UP, inject clean P UP
        if (keyCode == KeyEvent.KEYCODE_P && !down && event.isMetaPressed()
                && mWinPOffActive) {
            mWinPOffActive = false;
            param.setResult(true);  // block physical UP
            injectKeyUp(KeyEvent.KEYCODE_P, 0, event.getDeviceId());
            LogHelper.log(VerboseLevel.INFO, "L0: Win+P OFF UP → block physical, inject clean P UP");
            return true;
        }

        // Generic Win+key BLOCK UP consumer
        if (mPendingBlockedWinComboUp != 0 && !down
                && keyCode == mPendingBlockedWinComboUp
                && event.isMetaPressed()) {
            mPendingBlockedWinComboUp = 0;
            param.setResult(true);
            LogHelper.log(VerboseLevel.INFO, "L0: Win+key BLOCK UP → consumed, key=",
                    keyCodeToString(keyCode));
            return true;
        }

        return false;
    }

    // ----------------------------------------------------------------
    //  L0 post-shortcut Fn section (FnLock, Meta UP, Fn mapping, UP injection)
    //  Called AFTER all shortcut checks in L0.
    // ----------------------------------------------------------------

    /**
     * Process the entire Fn section of L0: FnLock toggle, Meta UP cleanup,
     * Fn key mapping (down), and Fn/restore UP injection.
     *
     * @param mKscInstance the captured KeyboardShortcutController instance (may be null)
     * @return true if the event was consumed (caller must return immediately)
     */
    public boolean processFnSection(int keyCode, boolean down, int repeatCount,
                                     KeyEvent event, XC_MethodHook.MethodHookParam param,
                                     Object mKscInstance) {
        // -- Win+` → FnLock toggle (pure Win+` only, no Alt/Shift/Ctrl) --
        if (keyCode == KeyEvent.KEYCODE_GRAVE
                && KeyInjector.modifiersMatch(event, true, false, false, false) && repeatCount == 0) {
            if (!cfg.fnLockEnabled) return false;
            param.setResult(true);
            if (down) {
                mConsumeNextMetaUp = true;
                cfg.fnKeyEnabled = !cfg.fnKeyEnabled;
                configIPC.pushUpdate(cfg);
                LogHelper.log(VerboseLevel.INFO, "L0: FnLock toggled -> ",
                        cfg.fnKeyEnabled ? "ON" : "OFF");
                showToast("Fn " + (cfg.fnKeyEnabled ? "ON" : "OFF"));
            }
            return true;
        }

        // -- Consume Meta UP after Win+` to prevent Start menu --
        if (mConsumeNextMetaUp
                && (keyCode == KeyEvent.KEYCODE_META_LEFT
                    || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                && !down) {
            param.setResult(true);
            mConsumeNextMetaUp = false;
            LogHelper.log(VerboseLevel.INFO, "L0: Meta UP consumed after Win+`");
            return true;
        }

        // -- Consume Meta UP after MetaAction.NONE to balance DOWN consumption --
        if (mConsumeMetaUpForNone
                && (keyCode == KeyEvent.KEYCODE_META_LEFT
                    || keyCode == KeyEvent.KEYCODE_META_RIGHT)
                && !down) {
            param.setResult(true);
            mConsumeMetaUpForNone = false;
            LogHelper.log(VerboseLevel.INFO, "L0: Meta UP consumed for NONE action balance");
            return true;
        }

        // -- Virtual Fn key processing (DOWN only) --
        if (down && repeatCount == 0 && isFnKeyboardDevice(event.getDeviceId())) {
            if (processFnKeyDown(keyCode, event, param)) return true;
        }

        // -- Fn/restore UP injection --
        return processFnKeyUp(keyCode, down, event, param);
    }

    // ----------------------------------------------------------------
    //  Internal: Fn key DOWN processing
    // ----------------------------------------------------------------

    /**
     * Process Fn key DOWN: either restore original key or map to F-key.
     * @return true if consumed
     */
    private boolean processFnKeyDown(int keyCode, KeyEvent event,
                                      XC_MethodHook.MethodHookParam param) {
        // Skip clean DOWN injected by restore path (avoid re-mapping loop)
        if (mRestoreDownPending.contains(keyCode)) return false;

        // DEBUG: Log all key events
        LogHelper.log(VerboseLevel.INFO, "L0: ALL KEYS keyCode=", String.valueOf(keyCode),
                " scanCode=", String.valueOf(event.getScanCode()),
                " fnEnabled=", String.valueOf(cfg.fnKeyEnabled));

        // Fn mapping: only pure Win (no Alt/Shift/Ctrl) qualifies as the Fn trigger
        boolean winOnly = KeyInjector.modifiersMatch(event, true, false, false, false);
        // XOR: FnLock ON + Win reverses; FnLock OFF + Win activates
        boolean fnActive = cfg.fnKeyEnabled ^ winOnly;
        int fKey = getFnTarget(event);

        // Preserve modifiers, stripping Meta/Win when it is the sole Fn toggle
        int origMeta = event.getMetaState();
        int passMeta = winOnly ? (origMeta & ~KeyEvent.META_META_MASK) : origMeta;

        // Debug: FnLock restore path
        if (cfg.fnKeyEnabled && winOnly) {
            LogHelper.log(VerboseLevel.INFO, "L0: FnLock restore",
                " key=", keyCodeToString(keyCode),
                " mapped=", fKey != 0 ? "F" + (fKey - 130) : "none",
                " winOnly=true fnActive=", String.valueOf(fnActive),
                fKey != 0 ? " -> intercept+inject original" : " -> pass");
        }

        // Restore original key (FnLock ON + Win held → suppress Fn, emit original key)
        if (!fnActive && fKey != 0) {
            LogHelper.log(VerboseLevel.INFO, "L0: restore original",
                " key=", keyCodeToString(keyCode),
                " normally=", "F" + (fKey - 130),
                " fnActive=false -> intercept+inject (meta=0x",
                Integer.toHexString(passMeta), ")");
            param.setResult(true);
            mRestoreDownPending.add(keyCode);
            mRestoreDownMeta.put(keyCode, passMeta);
            injectKeyDown(keyCode, passMeta, event.getDeviceId());
            return true;
        }

        // Map to F-key (FnLock ON no Win, or FnLock OFF + Win)
        if (fnActive && fKey != 0) {
            String fLabel = "F" + (fKey - 130);
            LogHelper.log(VerboseLevel.INFO, "L0: Fn",
                    cfg.fnKeyEnabled ? "(F" + (winOnly ? "->orig)" : ")")
                                     : "(Win->F" + ")",
                    " + ", keyCodeToString(keyCode),
                    " -> ", fLabel,
                    " meta=0x", Integer.toHexString(passMeta));
            if (cfg.fnToastEnabled) showToast(fLabel);

            param.setResult(true);
            mFnDownKeyMap.put(keyCode, fKey);
            mFnDownMetaMap.put(keyCode, passMeta);
            injectKeyDown(fKey, passMeta, event.getDeviceId());
            mFnDownKeys.add(keyCode);
            return true;
        }

        return false;
    }

    // ----------------------------------------------------------------
    //  Internal: Fn/restore UP injection
    // ----------------------------------------------------------------

    /**
     * Handle UP events for Fn-mapped and restore-injected keys.
     * @return true if consumed
     */
    private boolean processFnKeyUp(int keyCode, boolean down,
                                    KeyEvent event, XC_MethodHook.MethodHookParam param) {
        // Restore keys UP: block physical UP, inject clean UP
        if (!down && mRestoreDownPending.remove(keyCode)) {
            param.setResult(true);
            Integer meta = mRestoreDownMeta.remove(keyCode);
            injectKeyUp(keyCode, meta != null ? meta : 0, event.getDeviceId());
            return true;
        }

        // Fn-mapped keys UP: inject F-key UP
        if (!down && mFnDownKeys.remove(keyCode)) {
            param.setResult(true);
            Integer fKey = mFnDownKeyMap.remove(keyCode);
            Integer meta = mFnDownMetaMap.remove(keyCode);
            if (fKey != null) {
                injectKeyUp(fKey, meta != null ? meta : 0, event.getDeviceId());
            }
            return true;
        }

        return false;
    }

    // ----------------------------------------------------------------
    //  Keyboard device detection
    // ----------------------------------------------------------------

    /**
     * Check whether a given device ID belongs to a known Fn keyboard.
     * Caches results to avoid repeated InputDevice lookups on every key event.
     */
    public boolean isFnKeyboardDevice(int deviceId) {
        if (deviceId < 0) return false;
        if (mFnKeyboardDeviceIds.contains(deviceId)) return true;
        try {
            android.view.InputDevice dev = android.view.InputDevice.getDevice(deviceId);
            if (dev == null) return false;
            String key = String.format("%04x:%04x", dev.getVendorId(), dev.getProductId());
            // Built-in default
            if ("17ef:6271".equals(key)) {
                mFnKeyboardDeviceIds.add(deviceId);
                return true;
            }
            // Custom profiles
            if (cfg != null && cfg.fnCustomProfiles != null
                    && cfg.fnCustomProfiles.containsKey(key)) {
                mFnKeyboardDeviceIds.add(deviceId);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ----------------------------------------------------------------
    //  F-key target lookup
    // ----------------------------------------------------------------

    /**
     * Get the F-key target (131–142) for a given physical key event, or 0 if none.
     * Caches the mapping per profile key.
     */
    public int getFnTarget(KeyEvent event) {
        String currentKey = cfg != null ? cfg.fnProfileKey : "";
        if (mFnKeyCodeMap == null || !Objects.equals(currentKey, mFnMapProfileKey)) {
            mFnKeyCodeMap = buildFnKeyCodeMapping(currentKey);
            mFnMapProfileKey = currentKey;
        }
        Integer fKey = mFnKeyCodeMap.get(event.getKeyCode());
        return fKey != null ? fKey : 0;
    }

    // ----------------------------------------------------------------
    //  Fn keyCode → F-key mapping builder
    // ----------------------------------------------------------------

    /**
     * Build keyCode→F-key(131–142) mapping.
     * Uses keyCode because ZUI internally overwrites scanCode for custom keys (501–515).
     *
     * Keyboard top row layout (Lenovo Keyboard Pack For Yoga, 17ef:6271):
     *   ESC  Mute  Vol-  Vol+  MicMute  Bright-  Bright+  Screenshot  Maximize  Split  Star  Delete
     *   F1   F2    F3    F4    F5       F6       F7       F8          F9        F10    F11   F12
     */
    private Map<Integer, Integer> buildFnKeyCodeMapping(String profileKey) {
        // Auto-detect: match connected keyboard VID:PID
        if (profileKey == null || profileKey.isEmpty()) {
            profileKey = detectKeyboardProfile();
            if (profileKey == null) {
                LogHelper.log(VerboseLevel.DEBUG, "Fn auto-detect: no matching keyboard");
                return Collections.emptyMap();
            }
            LogHelper.log(VerboseLevel.INFO, "Fn auto-detected profile: ", profileKey);
        }

        Map<Integer, Integer> map = new HashMap<>();

        // Built-in default mapping (17ef:6271 Lenovo Yoga Keyboard)
        if ("17ef:6271".equals(profileKey)) {
            map.put(KeyEvent.KEYCODE_ESCAPE,       131);
            map.put(501,                           132);  // Mute → F2
            map.put(KeyEvent.KEYCODE_VOLUME_DOWN,  133);
            map.put(KeyEvent.KEYCODE_VOLUME_UP,    134);
            map.put(KeyEvent.KEYCODE_MUTE,         135);
            map.put(220,                           136);  // Bright-
            map.put(221,                           137);  // Bright+
            map.put(KeyEvent.KEYCODE_SYSRQ,        138);  // Screenshot → F8
            map.put(500,                           139);  // Maximize → F9
            map.put(504,                           140);  // Split → F10
            map.put(507,                           141);  // App1/Star → F11
            map.put(KeyEvent.KEYCODE_FORWARD_DEL,  142);  // Delete → F12
            return map;
        }

        // Load from custom profile
        if (cfg != null && cfg.fnCustomProfiles != null) {
            try {
                Object raw = cfg.fnCustomProfiles.get(profileKey);
                if (raw == null) {
                    LogHelper.log(VerboseLevel.DEBUG, "Fn profile not found: ", profileKey);
                    return map;
                }
                com.google.gson.Gson gson = new com.google.gson.Gson();
                moe.lovefirefly.betterzuikey.KeyboardProfiles.Profile profile =
                    gson.fromJson(gson.toJsonTree(raw),
                        moe.lovefirefly.betterzuikey.KeyboardProfiles.Profile.class);

                if (profile.getKeys() == null || profile.getKeys().isEmpty()) {
                    LogHelper.log(VerboseLevel.WARNING, "Fn profile has no keys: ", profileKey);
                    return map;
                }

                int loaded = 0;
                for (int i = 1; i <= 12; i++) {
                    String fk = "F" + i;
                    moe.lovefirefly.betterzuikey.KeyboardProfiles.KeyEntry entry =
                        profile.getKeys().get(fk);
                    if (entry == null || !entry.isValid()) continue;
                    if (entry.getKeyCode() > 0) {
                        map.put(entry.getKeyCode(), 130 + i);
                        loaded++;
                    }
                }
                LogHelper.log(VerboseLevel.INFO, "Fn profile loaded: ",
                    profile.getFriendlyName(), " (", String.valueOf(loaded), "/12)");
            } catch (Exception e) {
                LogHelper.log(VerboseLevel.WARNING, "Fn profile load failed: ",
                    profileKey, " ", e.getMessage());
            }
        }
        return map;
    }

    // ----------------------------------------------------------------
    //  Keyboard profile auto-detection
    // ----------------------------------------------------------------

    /** Detect connected keyboard and match to a known profile. Returns profile key or null. */
    private String detectKeyboardProfile() {
        try {
            // We need a Context; use the static ActivityThread pattern
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            android.content.Context ctx = (android.content.Context)
                    at.getClass().getMethod("getSystemContext").invoke(at);
            android.hardware.input.InputManager im =
                (android.hardware.input.InputManager)
                    ctx.getSystemService(android.content.Context.INPUT_SERVICE);
            if (im == null) return null;

            for (int id : im.getInputDeviceIds()) {
                android.view.InputDevice dev = im.getInputDevice(id);
                if (dev == null) continue;
                String key = String.format("%04x:%04x", dev.getVendorId(), dev.getProductId());
                if ("17ef:6271".equals(key)) return key;
                if (cfg != null && cfg.fnCustomProfiles != null
                        && cfg.fnCustomProfiles.containsKey(key)) {
                    return key;
                }
            }
        } catch (Throwable t) {
            LogHelper.log(VerboseLevel.DEBUG, "detectKeyboardProfile error:", t.getMessage());
        }
        return null;
    }

    // ----------------------------------------------------------------
    //  Misc helpers (used by MainHook)
    // ----------------------------------------------------------------

    /** Whether a combo UP cleanup is pending (used for Ctrl+Shift+T UP logic in L0). */
    public boolean hasPendingBlockedComboUp() {
        return mPendingBlockedWinComboUp != 0;
    }
}
