# BetterZUIKey Help

## 📖 Overview

BetterZUIKey is an Xposed module for customizing keyboard shortcut behavior on ZUI/ZUXOS systems.

With this module, you can:
- 🚫 Disable unwanted shortcuts
- 🔄 Switch shortcut behavior to AOSP native handling (bypass ZUI telemetry)
- 📱 Create per-app custom shortcut templates
- ⌨️ Virtual Fn keys (Win simulated Fn → F1~F12)
- 🌍 Region-specific behavior overrides (ROW/China/Korea)
- ♿ AOSP accessibility switches (Bounce/Mouse/Sticky/Slow keys)
- 🔍 Search & filter shortcuts by name, state, and override mode

---

## 🏠 Home Page

The home page displays a **status card** showing real-time module health:

| Color | Status | Meaning |
|-------|--------|---------|
| 🟢 Green | Active + Root | Module correctly scoped to system framework, Root granted |
| 🟠 Amber | Active, No Root | Correct scope but no Root (some features limited) |
| 🟡 Yellow | Wrong Scope | Module loaded but not in system_server — set scope to **System Framework** in LSPosed |
| 🔴 Red | Inactive | Module not activated — check LSPosed Manager |

Other features:
- **Pull to refresh** to recheck module status and Root
- **Tap status card** to attempt opening LSPosed Manager
- **Amber warning bar** appears on errors — tap to copy error info

---

## ⌨️ Shortcuts

The **Shortcuts** tab lists all supported shortcuts with per-key override controls. See [Appendix A](#-appendix-a--shortcut-reference) for the full shortcut reference.

### 🔍 Search & Filter

- **Search**: keywords separated by spaces or `+` for AND-search. e.g. `Win + Ctrl` matches shortcuts containing both "Win" and "Ctrl".
- **Filter**: narrow by **system switch state** (ON/OFF/No Switch) and **override mode** (Keep Default / Use ZUX / Use AOSP / Off / Block).


### 🎯 Override Modes

Each shortcut can independently select one of the following modes:

| Mode | Description |
|------|-------------|
| **Keep Default** | Follows ZUX system switch: ON → ZUX, OFF → pass-through |
| **Use ZUX** | Enable ZUX shortcut behavior, ignoring system switch state |
| **Use AOSP** | Block ZUX handling, delegate to Android native implementation |
| **Off** | Disable interception; events pass through to the foreground app |
| **Block** | Consume the key event without any action; neither system nor app receives it |

#### 🔄 Auto Switch Toggle

When you change the dropdown from Keep Default, if the system switch exists and is OFF, the module will **automatically turn it ON**.

> 💡 **Keep all system switches ON** to ensure the module can intercept shortcuts. If a shortcut's system switch is OFF, your override setting may have no effect — ZUI won't deliver the event to the module at all.

**Exceptions (will not auto-toggle):**

| Shortcut | Reason |
|----------|--------|
| `Win + Alt + 3` (Bounce Keys) | Switch is a feature toggle (enables/disables the accessibility feature itself), not a shortcut interception switch |
| `Win + Alt + 4` (Mouse Keys) | Same as above |
| `Win + Alt + 5` (Sticky Keys) | Same as above |
| `Win + Alt + 6` (Slow Keys) | Same as above |
| Ctrl card | Switch controls "Long-press Ctrl", dropdown controls "Ctrl + /" — two independent functions |

---

## 📱 App Templates

App templates allow per-app shortcut overrides. When the matched app is in the foreground, the template overrides take precedence over global settings. Unlisted shortcuts inherit global settings.

> 💡 If an app matches multiple templates, **the first** enabled template in the list wins.

### Example use cases:
- Passthrough Win+Tab in remote desktop apps so the remote host receives the window-switch command
- Block Ctrl+Shift+T in a browser so the browser receives the key and restores a tab

### How-To:

**Create a template**
1. Switch to the **Templates** tab
2. Tap **New Template**
3. Enter a name — duplicate names are auto-renamed

**Bind apps**
1. Tap **Select Apps** on the template card
2. Search and check target apps (multi-select)
3. Binding an app to multiple templates is not recommended — conflict warnings will appear
4. Tap **Done**

**Edit shortcut overrides**
1. Tap the template card to enter the editor
2. Each shortcut can be independently set:
   - **Switch**: enable or disable the shortcut
   - **Override mode**: Keep Default / Use ZUX / Use AOSP / Off / Block
   - **Inherit Global**: fall back to the global setting
3. Overridden keys are highlighted in blue — the editor also supports **search & filter**
4. Auto-saved on back

**Manage templates**
- Duplicate names get auto-suffix (-2, -3, …)
- Copy a template with optional app-list copy; first-match-wins applies
- Long-press drag to reorder

> ⚠️ Template changes take effect immediately when the target app comes to the foreground.

---

## ⚙️ Settings

### 🔌 Module Master Switch

Global on/off. OFF = all hooks disabled, ZUI default behavior for all shortcuts.

### ⌨️ Virtual Fn Key

Simulate Fn key functionality via software, mapping top-row keys to F1~F12.

> ⚠️ This module has compatibility issues with [KeyMapper](https://github.com/keymapperorg/KeyMapper/releases) in "Expert Mode". Expert Mode reads scan codes instead of keyCodes and cannot correctly map ZUI keyboard special keys (mute, split screen, etc.). Use the built-in Virtual Fn feature instead.

#### Trigger Modes:

**🔒 FnLock Mode**
- Press `` Win + ` `` (backtick above Tab) to toggle Fn lock
- When locked, single-press top-row keys output F1~F12
- `` Win + ` `` is fully intercepted
- Press again to unlock

**⌨️ Temporary Fn Mode**
- When FnLock is OFF, hold `Win` + top-row key for temporary F1~F12
- When FnLock is ON, hold `Win` + top-row key for original function (volume, brightness, etc.)

#### Default Mapping (Lenovo Yoga Keyboard 17ef:6271)

| Top-Row Key | Fn Map | Top-Row Key | Fn Map |
|-------------|--------|-------------|--------|
| ESC | F1 | Brightness+ | F7 |
| Mute | F2 | Screenshot | F8 |
| Volume- | F3 | Maximize | F9 |
| Volume+ | F4 | Split Screen | F10 |
| Mic Mute | F5 | Star (Custom) | F11 |
| Brightness- | F6 | Delete | F12 |

#### ⚠️ System Modifier Key Remapping

Android's "Modifier Keys" remapping (Settings → Physical Keyboard → Modifier keys) allows swapping Caps Lock, Ctrl, Meta, Alt, and ESC.

The built-in default profile **maps ESC (keyCode 111) to F1**. If modifier key remapping is enabled (e.g. ESC → BACK), the default Fn profile will fail to match ESC, breaking virtual Fn.

**Solutions**:
- **Recommended**: Go to [Keyboard Detect](app://keyboarddetect), record a new profile under your current remapping state, then import and activate it
- Or: disable system modifier key remapping and use the built-in default profile

> 💡 Remapping state is in `/data/system/input-manager-state.xml`. The module matches by keyCode, so remapped keys no longer match profile entries.

#### Keyboard Profiles

Different keyboards have different scan codes. The module includes a default profile for Lenovo Yoga Keyboard (17ef:6271). For other keyboards, create or import a custom JSON profile.

**Setup:**
1. Go to [Virtual Fn Settings](app://fnsettings)
2. Select or import a matching keyboard profile
3. Manage profiles via [Profile Management](app://profilemanage)

See [Appendix B](#-appendix-b--creating-keyboard-profiles) for detailed instructions.

### 🌐 OneVision Feature

Lenovo OneVision (SuperConnect) cross-screen collaboration. Controls whether OneVision-related shortcuts are intercepted and overridden by the module.

### 🎨 [Appearance](app://appearance)

- **Night Mode**: Auto (follow system), always on, always off
- **Material You Dynamic Color**: Extract theme color from wallpaper

### 📊 Log Level

Control LSPosed module log output verbosity:

| Level | Description |
|-------|-------------|
| SILENT | No log output |
| ERROR | Errors only |
| WARNING | Warnings and errors |
| INFO | Key events (startup, config changes, etc.) |
| DEBUG | Detailed debugging info |

> 💡 View logs via LSPosed Manager or logcat.

### 🌍 Region Override

ZUI determines shortcut behavior based on two system properties. See [Appendix C](#-appendix-c--region-override-system-properties) for details.

#### Region

| Option | Description |
|--------|-------------|
| Default | No override, use device original value |
| China (CN) | Force China region, Meta hold cycles language |
| International (ROW) | Force overseas region, Ctrl+Shift/Alt+Shift switch IME |
| Custom | Enter any region code (e.g. "TW", "JP") |

#### Country/Region

| Option | Description |
|--------|-------------|
| Default | No override, use device original value |
| Korea (KR) | Force Korea region, enable Alt_RIGHT for Korean |
| Custom | Enter any country code |

> ⚠️ Changing the region may cause abnormal shortcut behavior. Understand the differences before modifying.

---

## ⚠ Important Notes

1. This module requires **LSPosed 2.0.x+**. Enable it in the LSPosed manager and set scope to **System Framework**.

2. Modifying shortcut behavior may cause system issues or conflicts. Understand the effects before making changes.

3. Some shortcuts are ineffective on the lock screen or in certain scenarios — this is a ZUI system limitation, not a module issue.

4. If keyboard behavior becomes abnormal, turn off the module master switch, reboot, and troubleshoot step by step.

---

## 📦 Version

- **Version**: BetterZUIKey v1.2.0-beta1
- **Project**: [GitHub](https://github.com/CommandPrompt-Wang/BetterZUIKey)

---

---

## Appendix A — Shortcut Reference

### Win + Letter

| Shortcut | Function | ZUI | AOSP |
|----------|----------|:---:|:----:|
| `Win + D` | Show desktop | | ✅ |
| `Win + S` | Global search | ✅ | |
| `Win + A` | Hide/show taskbar | ✅ | |
| `Win + Back` | Send ESC / Back | ✅ | |
| `Win + E` | Open file manager | ✅ | |
| `Win + I` | Open settings | | ✅ |
| `Win + L` | Lock screen | ✅ | |
| `Win + M` | Minimize window | | ✅ |
| `Win + N` | Notification panel | | ✅ |
| `Win + P` | Toggle PC mode | ✅ | |
| `Win + W` | Close foreground app (protects 5 system apps) | ✅ | |
| `Win + 1~8` | Open Dock app at position | ✅ | |
| `Win + Tab` | Recent tasks | ✅ | ✅ |

### Win + Arrow Keys

| Shortcut | Function | ZUI | AOSP |
|----------|----------|:---:|:----:|
| `Win + ↑ / Win + ↓` | Maximize / Restore window | | ✅ |
| `Win + ← / Win + →` | Split-screen left / right | ✅ | |

### Ctrl / Alt / Shift Combos

| Shortcut | Function | ZUI | AOSP |
|----------|----------|:---:|:----:|
| `Long-press Ctrl` | Show shortcut menu (requires toggle ON + Ctrl+/ = Keep Default) | ✅ | |
| `Ctrl + /` | Shortcut menu behavior mode | ✅ | |
| `Ctrl + Shift` | Switch input method (ROW only) | ✅ | |
| `Ctrl + Space` | Dual-mode: switch IME language + pass-through to app | ✅ | |
| `Ctrl + Shift + T` | Toggle touchpad | ✅ | |
| `Alt + Shift` | Switch language (ROW only) | ✅ | |
| `Ctrl + Enter` | Passthrough when QQ is foreground | ✅ | |
| `Alt + Tab` | Recent tasks switcher | ✅ | ✅ |

### ZUI Physical Keys (ZUI keyboard only)

| Key Code | Name | Function |
|----------|------|----------|
| 501 | Mute | Mute toggle |
| 502 | Touchpad | Touchpad toggle |
| 504 | Split Screen | Split-screen toggle |
| 505 | SuperConnect | Launch SuperConnect |
| 507 | App1 | Custom key (short press / long press → settings) |
| 508 | App2 | Custom key (short press / long press → settings) |
| 509 | Search | Global search |
| 510 | Settings | ⚠ Disabled when unlocked (ZUI Bug) |
| 511 | Fn Lock | Fn lock toggle + LED |
| 512 | Backlight | Keyboard backlight cycle |
| 514 | Touchpad Up | Open notification panel |
| 515 | Screen Lock | Lock screen |

### Screenshot / Special Keys

| Key | Function |
|-----|----------|
| Print Screen (short) | Region screenshot |
| Print Screen (long) | Full-screen screenshot |
| Caps Lock | Show Toast + pass-through to app |
| Meta (single press) | Start menu; ~300ms hold required in Off/Block modes |
| Meta ROW (short) | Switch language (requires ROW + special keyboard) |
| Meta ROW (long) | Voice assistant (requires ROW + special keyboard) |
| Meta Non-ROW (hold) | Continuous language switch (requires Non-ROW + special keyboard) |
| 520 Keyboard Restore | Disable physical keyboard |
| 521 Keyboard Flip | Enable physical keyboard + show on-screen keyboard |
| Alt_RIGHT (KR) | Korean language switch |

### AOSP Accessibility Keys

| Shortcut | Function | Description |
|----------|----------|-------------|
| `Win + Alt + 3` | Bounce Keys | Ignore rapid repeated keystrokes |
| `Win + Alt + 4` | Mouse Keys | Control mouse pointer with keyboard |
| `Win + Alt + 5` | Sticky Keys | Press combo keys one at a time |
| `Win + Alt + 6` | Slow Keys | Require key to be held before registering |

> 💡 AOSP accessibility keys are controlled via `Settings.Secure`, synced with system accessibility settings.

---

## Appendix B — Creating Keyboard Profiles

Two approaches:

**Method 1: Semi-Automatic (Recommended)**

1. Go to [Virtual Fn](app://fnsettings) → [Keyboard Detect](app://keyboarddetect)
2. Press any key to auto-detect keyboard VID:PID
3. Press each of the 12 top-row keys, then tap "Fill" after each to assign the keyCode to the selected F-key slot
   - Auto-advances to the next F-key after filling
   - If you make a mistake, re-select the F-key and press the correct key to overwrite
4. Tap **Copy Config** to get the filled JSON template for direct import
   - Customize `name` and `friendlyName` fields as needed
   - Unfilled F-keys will retain a placeholder marker

**Method 2: Manual**

Write a JSON file in the following format and import via [Profile Management](app://profilemanage) → Import:

```json
{
  "profiles": {
    "<vendor:product>": {
      "name": "keyboard-name",
      "friendlyName": "Display Name",
      "keys": {
        "F1":  { "keyCode": <Android keyCode> },
        "F2":  { "keyCode": <Android keyCode> }
      }
    }
  }
}
```

**Example** (Lenovo XiaoXin Pad Pro GT keyboard, `17ef:6271`):

```json
{
  "profiles": {
    "17ef:6271": {
      "name": "Lenovo Keyboard Pack For Yoga Keyboard",
      "friendlyName": "Lenovo XiaoXin Pad Pro GT Keyboard",
      "keys": {
        "F1":  { "keyCode": 1 },
        "F2":  { "keyCode": 501 },
        "F3":  { "keyCode": 25 },
        "F4":  { "keyCode": 24 }
      }
    }
  }
}
```

**Field descriptions:**
- `<vendor:product>` — USB VID:PID in lowercase hex (model identifier, not serial)
- `keys` — F1~F12 physical key mappings using Android KeyEvent keyCode (not Linux key code)
- Fn trigger injects corresponding F-key (F1=131, F2=132, ..., F12=142)

> 💡 keyCode can be viewed in real-time on the Keyboard Detect page, or in LSPosed logs via `ALL KEYS keyCode=`.

---

## Appendix C — Region Override System Properties

The module hooks `SystemProperties.get()` to override:
- `ro.config.lgsi.region` — Region code
- `ro.config.lgsi.countrycode` — Country code

**ZUI decision logic:**

| Mode | Condition |
|------|-----------|
| China mode | `value` is empty or equals "CN" |
| ROW (international) mode | Non-empty and not "CN" |
| Korea mode | `countrycode` uppercased equals "KR" |
