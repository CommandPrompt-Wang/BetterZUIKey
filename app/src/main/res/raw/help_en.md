# BetterZUIKey Help

## 📖 Overview

BetterZUIKey is an Xposed module for customizing keyboard shortcut behavior on ZUI/ZUXOS systems.

With this module, you can:
- 🚫 Disable unwanted shortcuts
- 🔄 Switch shortcut behavior to AOSP native handling
- 📱 Create per-app custom shortcut templates
- ⌨️ Virtual Fn keys (Win simulated Fn → F1~F12)
- 🌍 Region-specific behavior overrides (ROW/China/Korea)
- ♿ AOSP accessibility switches (Bounce/Mouse/Sticky/Slow keys)

---

## 🎯 Override Modes

Each shortcut can independently select one of the following override modes:

| Mode | Description |
|------|-------------|
| **Keep Default** | Maintain ZUI original logic: ON → ZUI, OFF → pass-through |
| **Use ZUI** | Force ZUI shortcut behavior, ignoring system switch state |
| **Use AOSP** | Block ZUI handling, delegate to Android native implementation |
| **Off** | Completely disable interception; events pass through to the foreground app |
| **Block** | Consume the key event without any action; neither system nor app receives it |

### 🔄 Auto Switch Toggle
When you select a non-default mode from the dropdown, the corresponding switch will automatically toggle ON — no need to manually tap the switch first.

---

## 📱 App Templates

You can create shortcut templates for specific apps. Template configs are applied when the matched app is in the foreground.

### Creating a Template
1. Swipe to the **Templates** tab
2. Tap **New Template**, give it a name
3. Tap **Select Apps** and pick target apps from the list
4. Tap the template card to enter the **Template Editor**
5. Each shortcut can override the global default or **Inherit Global**

### Conflict Detection
If you select an app that already belongs to another template, a warning toast will appear. One app can only belong to one template at a time.

---

## ⌨️ Virtual Fn Keys

Virtual Fn keys allow you to simulate F1~F12 using a keyboard's multimedia keys or proprietary scan codes.

### Setup
1. Go to **Settings → Virtual Fn Key**
2. Enable the master switch and Toast debugging (optional)
3. Select or import a keyboard profile
4. Turn Fn Lock ON with **Win + `** (backtick)
5. Press F1~F12 mapped keys

### Creating a Keyboard Profile
- Go to **Virtual Fn → Create Profile** or **Import Profile**
- **Keyboard Detect** page: press a physical F-key, then fill the detected keyCode/scanCode into the F1~F12 table
- **Copy Config** to copy the JSON template, then import it

### Importing Profiles
- **From File**: Select a `.json` file
- **From Text**: Paste JSON directly
- Format: `{ "profiles": { "VID:PID": { "name": "...", "keys": { "F1": { "keyCode": 131 }, ... } } } }`

---

## ⚙️ Settings

| Setting | Description |
|---------|-------------|
| **Module Master Switch** | Global on/off for all hooks. OFF = ZUI default behavior |
| **Virtual Fn Key** | Manage Fn key profiles and mappings |
| **OneVision Feature** | Lenovo OneVision cross-screen collaboration shortcuts |
| **Appearance** | Night mode and Material You dynamic color |
| **Log Level** | Control Xposed module log verbosity |
| **Region** | Override `ro.config.lgsi.region` system property |
| **Country/Region** | Override `ro.config.lgsi.countrycode` system property |
| **Language** | App display language (English / 简体中文 / Follow System) |

---

## ⚠ Important Notes

- This module requires **Xposed framework** (LSPosed / LSPatch / etc.)
- **Scope**: Must hook `android` (system_server) — check your Xposed manager
- After changing settings, switches may take effect on the next key press or app restart
- The **AOSP accessibility switches** (Bounce/Mouse/Sticky/Slow keys) control `Settings.Secure` values and require `WRITE_SECURE_SETTINGS` permission — use the **su grant** button in the warning card if direct writing fails

---

## 🔗 Related Pages

- [Virtual Fn Settings](app://fnsettings)
- [Keyboard Detect](app://keyboarddetect)
- [Profile Management](app://profilemanage)
- [Appearance Settings](app://appearance)
- [AOSP Accessibility Switches](app://aosp)
