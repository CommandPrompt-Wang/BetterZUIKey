package moe.lovefirefly.betterzuikey.Config;

import moe.lovefirefly.betterzuikey.BuildConfig;
import moe.lovefirefly.betterzuikey.Utils.LogHelper;
import moe.lovefirefly.betterzuikey.Region.RegionProfile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * ZUXOS 键盘快捷键配
 * 每个快捷键有独立的启用开关和自定义行
 */
public class Config {
    // App 私有路径，system 进程无写权限时用 su echo 写入
    public static final String CONFIG_PATH =
            String.format("/data/data/%s/config.json", BuildConfig.APPLICATION_ID);

    // ================================================================
    // 一、Win/Meta + 字母组合
    // ================================================================

    /** Win+D 回到桌面 (L3 AOSP, L1 仅埋 */
    public SwitchState switchWinD = SwitchState.ON;
    public OverrideMode overrideWinD = OverrideMode.OFF; // 要拦截需 Hook L3 PhoneWindowManager

    /** Win+S 全局搜索 (L4 type=300) */
    public SwitchState switchWinS = SwitchState.ON;
    public OverrideMode overrideWinS = OverrideMode.FOLLOW_SYSTEM;

    /** Win+A 隐藏/显示任务(L4 type=302) */
    public SwitchState switchWinA = SwitchState.ON;
    public OverrideMode overrideWinA = OverrideMode.FOLLOW_SYSTEM;

    /** Win+Back 发ESC (L0 L4 type=306) */
    public SwitchState switchWinBack = SwitchState.ON;
    public OverrideMode overrideWinBack = OverrideMode.FOLLOW_SYSTEM;

    /** Win+E 打开文件管理(L4 type=307) */
    public SwitchState switchWinE = SwitchState.ON;
    public OverrideMode overrideWinE = OverrideMode.FOLLOW_SYSTEM;

    /** Win+I 打开设置 (L2 delegate + AOSP L3 type=7) */
    public SwitchState switchWinI = SwitchState.ON;
    public OverrideMode overrideWinI = OverrideMode.OFF;

    /** Win+L 锁屏 (L0 injectKeyEvent(26) POWER) */
    public SwitchState switchWinL = SwitchState.ON;
    public OverrideMode overrideWinL = OverrideMode.FOLLOW_SYSTEM;

    /** Win+M 最小化自由窗口 (L3 AOSP type=201) */
    public SwitchState switchWinM = SwitchState.ON;
    public OverrideMode overrideWinM = OverrideMode.OFF;

    /** Win+N 通知面板 (L1 仅埋开 实际操作AOSP L3 type=8) */
    public SwitchState switchWinN = SwitchState.ON;
    public OverrideMode overrideWinN = OverrideMode.OFF;

    /** Win+P 切换 PC 模式 (L0 直接调用 switchPcMode) */
    public SwitchState switchWinP = SwitchState.ON;
    public OverrideMode overrideWinP = OverrideMode.FOLLOW_SYSTEM;

    /** Win+W 强制关闭前台应用 (L4 type=305) */
    public SwitchState switchWinW = SwitchState.ON;
    public OverrideMode overrideWinW = OverrideMode.FOLLOW_SYSTEM;

    /** Win+1~8 打开 Dock 栏对应应(L4 type=309) */
    public SwitchState switchWinNumber = SwitchState.ON;
    public OverrideMode overrideWinNumber = OverrideMode.FOLLOW_SYSTEM;

    /** Win+Tab 最近任(ZUI L0 launchRecent + AOSP L3 type=2 showRecentApps) */
    public SwitchState switchWinTab = SwitchState.ON;
    public OverrideMode overrideWinTab = OverrideMode.FOLLOW_SYSTEM;

    // ================================================================
    // 二、Win/Meta + 功能
    // ================================================================

    /** Win+↑/↓ 窗口最大化/还原 (L3 AOSP type=53/52, 共用开关 keyboard_combo_ud_arrow) */
    public SwitchState switchWinUp = SwitchState.ON;
    public OverrideMode overrideWinUp = OverrideMode.OFF;

    /** Win+←/→ 分屏 (L4 type=312, 共用开关 keyboard_combo_lr_arrow) */
    public SwitchState switchWinLeft = SwitchState.ON;
    public OverrideMode overrideWinLeft = OverrideMode.FOLLOW_SYSTEM;

    // ================================================================
    // 三、Ctrl / Alt / Shift 组合
    // ================================================================

    /** Ctrl+/ 快捷键菜单 (L3 AOSP type=12, 无系统开关, 始终启用) */
    public OverrideMode overrideCtrlSlash = OverrideMode.OFF;

    /** Ctrl 长按快捷键菜单 (L1 type=12, 通过 keyboard_combo_ctrl_3 开关控制) */
    public SwitchState switchCtrlLongPress = SwitchState.ON;

    /** Ctrl+Shift 切换输入ROW (L1 type=311 L4) */
    public SwitchState switchCtrlShift = SwitchState.ON;
    public OverrideMode overrideCtrlShift = OverrideMode.FOLLOW_SYSTEM;

    /** Alt+Shift 切换语言 ROW (L1 type=310 L4) */
    public SwitchState switchAltShift = SwitchState.ON;
    public OverrideMode overrideAltShift = OverrideMode.FOLLOW_SYSTEM;

    /** Ctrl+Shift+T 切换触控(L4 type=308) */
    public SwitchState switchCtrlShiftT = SwitchState.ON;
    public OverrideMode overrideCtrlShiftT = OverrideMode.FOLLOW_SYSTEM;

    /** Ctrl+Space 放行给前台应(L0 条件拦截) */
    public SwitchState switchCtrlSpace = SwitchState.ON;
    public OverrideMode overrideCtrlSpace = OverrideMode.OFF; // 默认就是透传

    /** Ctrl+Enter QQ 前台条件拦截 (L0) */
    public SwitchState switchCtrlEnter = SwitchState.ON;
    public OverrideMode overrideCtrlEnter = OverrideMode.OFF; // 默认就是透传

    /** Alt+Tab 最近任务切(L0) */
    public SwitchState switchAltTab = SwitchState.ON;
    public OverrideMode overrideAltTab = OverrideMode.FOLLOW_SYSTEM;

    // ================================================================
    // 四、ZUI 专用物理
    // ================================================================

    /** 501 静音*/
    public SwitchState switchKeyMute = SwitchState.ON;
    public OverrideMode overrideMute = OverrideMode.FOLLOW_SYSTEM;

    /** 502 触控板开*/
    public SwitchState switchKeyTouchpad = SwitchState.ON;
    public OverrideMode overrideTouchpad = OverrideMode.FOLLOW_SYSTEM;

    /** 504 分屏*/
    public SwitchState switchKeySplitScreen = SwitchState.ON;
    public OverrideMode overrideSplitScreen = OverrideMode.FOLLOW_SYSTEM;

    /** 505 超级互联 */
    public SwitchState switchKeySuperConnect = SwitchState.ON;
    public OverrideMode overrideSuperConnect = OverrideMode.FOLLOW_SYSTEM;

    /** 507 App1 自定义行*/
    public SwitchState switchKeyApp1 = SwitchState.ON;
    public Behavior app1ShortPressBehavior = Behavior.AI_SUMMARY;
    public OverrideMode app1LongPressOverride = OverrideMode.FOLLOW_SYSTEM;

    /** 508 App2 自定义行*/
    public SwitchState switchKeyApp2 = SwitchState.ON;
    public Behavior app2ShortPressBehavior = Behavior.AI_SUMMARY;
    public OverrideMode app2LongPressOverride = OverrideMode.FOLLOW_SYSTEM;

    /** 509 搜索*/
    public SwitchState switchKeySearch = SwitchState.ON;
    public OverrideMode overrideSearch = OverrideMode.FOLLOW_SYSTEM;

    /** 510 设置*/
    public SwitchState switchKeySettings = SwitchState.ON;
    public OverrideMode overrideSettings = OverrideMode.FOLLOW_SYSTEM;

    /** 511 Fn 锁定切换 */
    public SwitchState switchKeyFnLock = SwitchState.ON;
    public OverrideMode overrideFnLock = OverrideMode.FOLLOW_SYSTEM;

    /** 512 键盘背光 */
    public SwitchState switchKeyBacklight = SwitchState.ON;
    public OverrideMode overrideBacklight = OverrideMode.FOLLOW_SYSTEM;

    /** 514 触控板上打开通知面板 */
    public SwitchState switchKeyTpUp = SwitchState.ON;
    public OverrideMode overrideTpUp = OverrideMode.FOLLOW_SYSTEM;

    /** 515 锁屏*/
    public SwitchState switchKeyScreenLock = SwitchState.ON;
    public OverrideMode overrideScreenLock = OverrideMode.FOLLOW_SYSTEM;

    // ================================================================
    // 五、截/ 特殊
    // ================================================================

    /** Print Screen 短按 区域截图 */
    public SwitchState switchPrintScreenShort = SwitchState.ON;
    public OverrideMode overridePrintScreenShort = OverrideMode.FOLLOW_SYSTEM;

    /** Print Screen 长按 s 全屏截图 */
    public SwitchState switchPrintScreenLong = SwitchState.ON;
    public OverrideMode overridePrintScreenLong = OverrideMode.FOLLOW_SYSTEM;

    /** Caps Lock (115) 显示 Toast + 透传 */
    public SwitchState switchCapsLock = SwitchState.ON;
    public OverrideMode overrideCapsLock = OverrideMode.OFF;

    /** Meta (117) 单按 开始菜(L2 delegate + AOSP L3 type=21) */
    public SwitchState switchMetaSingle = SwitchState.ON;
    public OverrideMode overrideMetaSingle = OverrideMode.OFF;

    /** Meta (117) ROW 短按 切换语言 */
    public SwitchState switchMetaShortRow = SwitchState.ON;
    public OverrideMode overrideMetaShortRow = OverrideMode.FOLLOW_SYSTEM;

    /** Meta (117) ROW 长按 语音助手 */
    public SwitchState switchMetaLongRow = SwitchState.ON;
    public OverrideMode overrideMetaLongRow = OverrideMode.FOLLOW_SYSTEM;

    /** Meta (117) ROW 长按 连续切语言 */
    public SwitchState switchMetaHoldNonRow = SwitchState.ON;
    public OverrideMode overrideMetaHoldNonRow = OverrideMode.FOLLOW_SYSTEM;

    /** 520 键盘恢复 禁用物理键盘 */
    public SwitchState switchKeyKeyboardRestore = SwitchState.ON;
    public OverrideMode overrideKeyboardRestore = OverrideMode.FOLLOW_SYSTEM;

    /** 521 键盘翻转 启用物理键盘 + 弹出屏幕键盘 */
    public SwitchState switchKeyKeyboardReverse = SwitchState.ON;
    public OverrideMode overrideKeyboardReverse = OverrideMode.FOLLOW_SYSTEM;

    /** Alt_RIGHT (58) KR 韩国版切换语言 */
    public SwitchState switchAltRightKR = SwitchState.ON;
    public OverrideMode overrideAltRightKR = OverrideMode.FOLLOW_SYSTEM;

    // ================================================================
    // 六、全局开关（我们的设置，非系统状态）
    // ================================================================

    /** ZUX 键盘功能总开*/
    public boolean zuxKeyboardFuncEnabled = true;

    /** 是否已注入（Xposed 模块激活状态，Hook 自动设置*/
    public boolean injected = false;

    /** 注入失败原因（为空表示无错误*/
    public String injectError = "";

    /** OneVision 特性开*/
    public boolean oneVisionFeatureEnabled = true;


    /** 日志 Verbose 级别 */
    public LogHelper.VerboseLevel verboseLevel = LogHelper.VerboseLevel.INFO;

    /** 区域覆写 DEFAULT 表示不干预，使用系统原生区域设定 (ro.config.lgsi.region) */
    public RegionProfile regionOverride = RegionProfile.DEFAULT;

    /** 自定义区域值（regionOverride=CUSTOM 时生效，"TW"JP"*/
    public String regionCustomValue = "";

    /** 国家/地区覆写 (ro.config.lgsi.countrycode) 不干 "KR"=韩国 */
    public String countryOverride = "";

    /** 匹配系统主题色（关闭则强制亮色） */
    public boolean matchSystemTheme = true;

    /** 夜间模式=自动, 1=开 2=关闭 */
    public int nightMode = 0;

    /** 动态调色板 (Material You) */
    public boolean dynamicColorEnabled = false;

    // ================================================================
    // 七、区域差异化行为（独立开关）
    // 覆盖 RegionProfile 的默认值，允许混合搭配
    // ================================================================

    /** 键盘固件 ScanCode 覆写=不覆写；787345=特殊键盘固件触发 Meta 三级分发*/
    public int keyboardScanCode = 0;

    /** Ctrl+Shift 切输入法（ROW 特性，type=311*/
    public boolean rowInputMethodSwitch = true;
    /** Alt+Shift 切语言（ROW 特性，type=310*/
    public boolean rowLanguageSwitch = true;
    /** Meta 短按 &lt;2s 行为 */
    public MetaAction metaShortPressAction = MetaAction.DEFAULT;
    /** Meta 长按 s 行为 */
    public MetaAction metaLongPressAction = MetaAction.DEFAULT;
    /** Meta 按住行为（非 ROW 连续切语言，DOWN 后每 50ms 重复注入 204*/
    public MetaAction metaHoldAction = MetaAction.DEFAULT;
    /** Alt_RIGHT(58) 韩国版切语言 */
    public boolean krAltRightSwitch = true;
    /** AI 代理选择（App1/App2 AI_AGENT 行为及系统级 AI 入口*/
    public AiAgent aiAgent = AiAgent.DEFAULT;
    /** 文件管理器选择（Win+E 启动的目标） */
    public FileManager fileManager = FileManager.DEFAULT;
    /** AI 屏幕总结（非 ROW 特性，triggerSmartHunter(9)*/
    public boolean aiSummaryEnabled = true;

    // ================================================================
    // 八、AOSP 原生辅助键（Win+Alt+3~6
    // ZUI 未显式处理，直接AOSP 底层消费；入口在系统 UI 中被隐藏
    // ================================================================

    /** Win+Alt+3 防抖键（AOSP 原生辅助功能*/
    public SwitchState switchAospBounceKeys = SwitchState.ON;
    public OverrideMode overrideAospBounceKeys = OverrideMode.AOSP;
    /** Win+Alt+4 鼠标键（AOSP 原生辅助功能*/
    public SwitchState switchAospMouseKeys = SwitchState.ON;
    public OverrideMode overrideAospMouseKeys = OverrideMode.AOSP;
    /** Win+Alt+5 粘滞键（AOSP 原生辅助功能*/
    public SwitchState switchAospStickyKeys = SwitchState.ON;
    public OverrideMode overrideAospStickyKeys = OverrideMode.AOSP;
    /** Win+Alt+6 慢速键（AOSP 原生辅助功能*/
    public SwitchState switchAospSlowKeys = SwitchState.ON;
    public OverrideMode overrideAospSlowKeys = OverrideMode.AOSP;

    // ================================================================
    // 九、应用模针对特定前台 app 覆写快捷键行
    // 模板中未列出的键继承全局默认
    // ================================================================

    /** 快捷键模板列表（一个模板可应用到多app*/
    public List<KeyTemplate> templates = new ArrayList<>();

    // ================================================================
    // 十、虚Fn 
    // Fn = Win+第一 FnLock = Win+`
    // 键盘映射res/raw/keyboard_profiles.json 加载
    // ================================================================

    /** 虚拟 Fn 功能启用（默认关闭，需 Win+` 开启） */
    public boolean fnKeyEnabled = false;
    /** FnLock 启用（Win+` 切换*/
    public boolean fnLockEnabled = true;
    /** 当前使用的键profile key（如 "17ef:6271"），自动检*/
    public String fnProfileKey = "";
    /** 用户导入的自定义键盘配置 */
    public java.util.Map<String, moe.lovefirefly.betterzuikey.KeyboardProfiles.Profile> fnCustomProfiles = new java.util.LinkedHashMap<>();
    /** 触发 Fn 映射时弹Toast（调试用，显F1-F12*/
    public boolean fnToastEnabled = true;

    // ================================================================
    // 内部枚举定义
    // ================================================================

    /**
     * 系统侧开关状—反映系统是否支持/强制该快捷键
     * 这是第一层：系统能力检测结果
     */
    public enum SwitchState {
        /** 系统强制开UI 表现为灰色勾选，用户不可关闭 */
        FORCED_ON,
        /** 系统支持，用户可切换，默认开 */
        ON,
        /** 系统强制关闭 UI 表现为灰色未勾*/
        FORCED_OFF,
        /** 系统不支持，用户可切换，默认*/
        OFF;

        /** 该快捷键是否实际生效（FORCED_ON ON*/
        public boolean isEnabled() {
            return this == FORCED_ON || this == ON;
        }

        /** 用户是否可切换（Forced 状态） */
        public boolean isUserToggleable() {
            return this == ON || this == OFF;
        }
    }

    /**
     * 覆盖模式 替代旧的 Action 枚举
     * FOLLOW_SYSTEM: 跟随系统开关（ZUI 开则用 ZUI，关则透传
     * ZUI:          强制启用 ZUI 实现（拦截并执行 ZUI 行为
     * AOSP:         强制启用 AOSP 原生实现（拦ZUI，放行给 AOSP
     * OFF:          关闭（不拦截，事件透传给前台应用）
     * BLOCK:        忽略（消费事件，系统和应用都收不到）
     */
    public enum OverrideMode {
        FOLLOW_SYSTEM,
        ZUI,
        AOSP,
        OFF,
        BLOCK,
    }

    /**
     * @deprecated 已替换为 OverrideMode。保留以兼容旧配置文件的反序列化
     */
    @Deprecated
    public enum Action {
        DEFAULT,
        BLOCK,
        PASS_THROUGH,
        AOSP,
        CUSTOM,
        OPEN_SETTINGS,
    }

    /**
     * App1(507) / App2(508) 短按行为
     * 对应文档第六章：triggerAppKeyBehavior 分发
     */
    public enum Behavior {
        /** 启动用户指定的应用或 Launcher 快捷方式 */
        LAUNCH_APP_OR_SHORTCUT,
        /** 启动系统语音助手 */
        AI_ASSIST,
        /** 启动 AI 代理 (ROW: com.zui.ai.now / 非ROW: 联想乐语 */
        AI_AGENT,
        /** 切换快速设置开(WiFi/蓝牙/手电筒等) */
        TRIGGER_QUICK_SETTINGS,
        /** AI 全局输入 (连按两次 Shift+7) */
        AI_GLOBAL_INPUT,
        /** 打开指定网站 */
        OPEN_WEBSITE,
        /** 屏幕文字提取 (AI Lens) */
        TEXT_EXTRACTION,
        /** AI 屏幕总结 */
        AI_SUMMARY,
        /** 未配默认行为 */
        NONE,
    }

    /**
     * Meta 键（keyCode=117）短长按/按住行为
     * 对应文档第五Meta 三级分发逻辑
     */
    public enum MetaAction {
        /** 跟系统区域设定走（ZUI 原始行为*/
        DEFAULT,
        /** AOSP 开始菜单（type=21 triggerShowAllApps*/
        START_MENU,
        /** 切换输入法语言（injectKeyEvent 204*/
        SWITCH_LANGUAGE,
        /** 启动语音助手（launchAssistActionExternal*/
        VOICE_ASSIST,
        /** 按住连续切语言（DOWN 注入 204 + 400ms 后每 50ms 重复*/
        HOLD_SWITCH_LANGUAGE,
        /** 什么都不做 */
        NONE,
    }

    /**
     * AI 代理选择
     * 控制 App1/App2 AI_AGENT 行为以及系统AI 入口
     */
    public enum AiAgent {
        /** 跟系统区域设定走 */
        DEFAULT,
        /** ROW: com.zui.ai.now */
        ZUI_AI_NOW,
        /** 中国: 联想乐语*/
        LENOVO_LE_YU_YIN,
        /** 禁用 AI 代理 */
        NONE,
    }

    /**
     * 文件管理器选择
     * Win+E 启动的文件管理器
     */
    public enum FileManager {
        /** 跟系统区域设定走 */
        DEFAULT,
        /** ROW: Google Files */
        GOOGLE_FILES,
        /** 中国: ZUI 文件管理*/
        ZUI_FILES,
        /** 不启动文件管理器（等同于拦截*/
        NONE,
    }

    // ================================================================
    // 方法
    // ================================================================

    // ----------------------------------------------------------------
    //  Gson 实例（带 InstanceCreator：先填默认值，再用 JSON 覆盖
    // ----------------------------------------------------------------
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Config.class, (InstanceCreator<Config>) type -> {
                Config cfg = new Config();
                cfg.resetToDefault();
                return cfg;
            })
            .create();

    /**
     * JSON 文件加载配置
     * 先填充出厂默认值，再用文件中存在的字段覆盖 —新增字段不会丢默认值
     * 加载后自动从 Settings.System 刷新 SwitchState（实时反映系统开关状态）
     */
    public static Config load() {
        Config cfg;
        try (FileReader reader = new FileReader(CONFIG_PATH)) {
            cfg = GSON.fromJson(reader, Config.class);
        } catch (FileNotFoundException e) {
            // 首次启动，保存默认配
            cfg = new Config();
            cfg.resetToDefault();
            cfg.save();
        } catch (IOException e) {
            // 读取失败，回退默认
            cfg = new Config();
            cfg.resetToDefault();
        }
        // 将日志级别应用到 LogHelper
        LogHelper.currentLevel = cfg.verboseLevel;

        // 迁移旧配置：FORCED_ON/FO→ON/OFF（不再灰显）
        migrateSwitchStates(cfg);

        // State sync is handled at boot by system_server (MainHook).
        // App process does NOT read Settings.System — see ipc-contentprovider.md.
        return cfg;
    }

    /** 将旧的 FORCED_ON/FORCED_OFF 迁移到 ON/OFF，不再灰显开关 */
    private static void migrateSwitchStates(Config cfg) {
        boolean changed = false;
        for (java.lang.reflect.Field f : Config.class.getFields()) {
            if (f.getType() != SwitchState.class) continue;
            try {
                SwitchState v = (SwitchState) f.get(cfg);
                if (v == SwitchState.FORCED_ON) { f.set(cfg, SwitchState.ON); changed = true; }
                else if (v == SwitchState.FORCED_OFF) { f.set(cfg, SwitchState.OFF); changed = true; }
            } catch (Throwable ignored) { }
        }
        if (changed) cfg.save();
    }

    /**
     * 保存当前配置 JSON 文件
     * 仅在 App 进程（uid=u0_aXXX）调用，system_server 进程不应调用此方法
     * system app 的状态传递改ContentProvider
     */
    public String save() {
        try {
            String json = GSON.toJson(this);
            try (FileWriter writer = new FileWriter(CONFIG_PATH)) {
                writer.write(json);
                return null; // 成功
            }
        } catch (Exception e) {
            return "Config save failed: " + e.getMessage();
        }
    }

    /** Serialize config to JSON (reuses GSON with defaults-safe InstanceCreator). */
    public static String toJson(Config cfg) {
        return GSON.toJson(cfg);
    }

    /** Deserialize config from JSON (fills defaults for missing fields). */
    public static Config fromJson(String json) {
        return GSON.fromJson(json, Config.class);
    }

    /**
     * Write full config JSON to SharedPreferences + fix permissions.
     * GravityBox WorldReadablePrefs pattern: setExecutable + setReadable on
     * the shared_prefs directory so system_server (uid=1000) can traverse.
     */
    public static void syncToSharedPrefs(android.content.Context ctx, Config cfg) {
        try {
            String json = GSON.toJson(cfg);
            android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                    moe.lovefirefly.betterzuikey.RemotePrefProvider.PREF_FILE,
                    android.content.Context.MODE_PRIVATE);
            prefs.edit().putString("config_sync", json).commit();

            // GravityBox WorldReadablePrefs pattern: fix entire directory chain
            // so system_server (uid=1000) can traverse and read the prefs file.
            // Chain: /data/data/<pkg>/ → shared_prefs/ → betterzuikey_config.xml
            java.io.File dataDir = new java.io.File(ctx.getApplicationInfo().dataDir);
            if (dataDir.exists()) {
                dataDir.setExecutable(true, false);   // o+x: allow traversal
            }
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }
            java.io.File prefsFile = new java.io.File(prefsDir,
                moe.lovefirefly.betterzuikey.RemotePrefProvider.PREF_FILE + ".xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);   // o+r: allow reading
            }
            LogHelper.log(LogHelper.VerboseLevel.INFO,
                    "Config synced (", String.valueOf(json.length()), " bytes) + permissions fixed");
        } catch (Exception e) {
            LogHelper.log(LogHelper.VerboseLevel.WARNING,
                    "Config syncToSharedPrefs failed:", e.getMessage());
        }
    }

    /**
     * 将当前配置重置为安全默认值
     * 所有系统开关设FORCED_ON（未知状态→假设可用），
     * 调用 detectFromSystem() 可根据实际设备更新这些值
     */
    public void resetToDefault() {
        // 一、Win/Meta + 字母
        switchWinD = SwitchState.ON;        overrideWinD = OverrideMode.OFF;
        switchWinS = SwitchState.ON;        overrideWinS = OverrideMode.FOLLOW_SYSTEM;
        switchWinA = SwitchState.ON;        overrideWinA = OverrideMode.FOLLOW_SYSTEM;
        switchWinBack = SwitchState.ON;     overrideWinBack = OverrideMode.FOLLOW_SYSTEM;
        switchWinE = SwitchState.ON;        overrideWinE = OverrideMode.FOLLOW_SYSTEM;
        switchWinI = SwitchState.ON;        overrideWinI = OverrideMode.OFF;
        switchWinL = SwitchState.ON;        overrideWinL = OverrideMode.FOLLOW_SYSTEM;
        switchWinM = SwitchState.ON;        overrideWinM = OverrideMode.OFF;
        switchWinN = SwitchState.ON;        overrideWinN = OverrideMode.OFF;
        switchWinP = SwitchState.ON;        overrideWinP = OverrideMode.FOLLOW_SYSTEM;
        switchWinW = SwitchState.ON;        overrideWinW = OverrideMode.FOLLOW_SYSTEM;
        switchWinNumber = SwitchState.ON;   overrideWinNumber = OverrideMode.FOLLOW_SYSTEM;
        switchWinTab = SwitchState.ON;      overrideWinTab = OverrideMode.FOLLOW_SYSTEM;
        // 二、Win+功能
        switchWinUp = SwitchState.ON;       overrideWinUp = OverrideMode.OFF;
        switchWinLeft = SwitchState.ON;     overrideWinLeft = OverrideMode.FOLLOW_SYSTEM;
        // 三、Ctrl/Alt/Shift
        overrideCtrlSlash = OverrideMode.OFF;
        switchCtrlLongPress = SwitchState.ON;
        switchCtrlShift = SwitchState.ON;     overrideCtrlShift = OverrideMode.FOLLOW_SYSTEM;
        switchAltShift = SwitchState.ON;      overrideAltShift = OverrideMode.FOLLOW_SYSTEM;
        switchCtrlShiftT = SwitchState.ON;    overrideCtrlShiftT = OverrideMode.FOLLOW_SYSTEM;
        switchCtrlSpace = SwitchState.ON;     overrideCtrlSpace = OverrideMode.OFF;
        switchCtrlEnter = SwitchState.ON;     overrideCtrlEnter = OverrideMode.OFF;
        switchAltTab = SwitchState.ON;        overrideAltTab = OverrideMode.FOLLOW_SYSTEM;
        // 四、ZUI 专用
        switchKeyMute = SwitchState.ON;             overrideMute = OverrideMode.FOLLOW_SYSTEM;
        switchKeyTouchpad = SwitchState.ON;         overrideTouchpad = OverrideMode.FOLLOW_SYSTEM;
        switchKeySplitScreen = SwitchState.ON;      overrideSplitScreen = OverrideMode.FOLLOW_SYSTEM;
        switchKeySuperConnect = SwitchState.ON;     overrideSuperConnect = OverrideMode.FOLLOW_SYSTEM;
        switchKeyApp1 = SwitchState.ON;
        app1ShortPressBehavior = Behavior.AI_SUMMARY;
        app1LongPressOverride = OverrideMode.FOLLOW_SYSTEM;
        switchKeyApp2 = SwitchState.ON;
        app2ShortPressBehavior = Behavior.AI_SUMMARY;
        app2LongPressOverride = OverrideMode.FOLLOW_SYSTEM;
        switchKeySearch = SwitchState.ON;           overrideSearch = OverrideMode.FOLLOW_SYSTEM;
        switchKeySettings = SwitchState.ON;         overrideSettings = OverrideMode.FOLLOW_SYSTEM;
        switchKeyFnLock = SwitchState.ON;           overrideFnLock = OverrideMode.FOLLOW_SYSTEM;
        switchKeyBacklight = SwitchState.ON;        overrideBacklight = OverrideMode.FOLLOW_SYSTEM;
        switchKeyTpUp = SwitchState.ON;             overrideTpUp = OverrideMode.FOLLOW_SYSTEM;
        switchKeyScreenLock = SwitchState.ON;       overrideScreenLock = OverrideMode.FOLLOW_SYSTEM;
        // 五、截特殊
        switchPrintScreenShort = SwitchState.ON;    overridePrintScreenShort = OverrideMode.FOLLOW_SYSTEM;
        switchPrintScreenLong = SwitchState.ON;     overridePrintScreenLong = OverrideMode.FOLLOW_SYSTEM;
        switchCapsLock = SwitchState.ON;            overrideCapsLock = OverrideMode.OFF;
        switchMetaSingle = SwitchState.ON;          overrideMetaSingle = OverrideMode.OFF;
        switchMetaShortRow = SwitchState.ON;        overrideMetaShortRow = OverrideMode.FOLLOW_SYSTEM;
        switchMetaLongRow = SwitchState.ON;         overrideMetaLongRow = OverrideMode.FOLLOW_SYSTEM;
        switchMetaHoldNonRow = SwitchState.ON;      overrideMetaHoldNonRow = OverrideMode.FOLLOW_SYSTEM;
        switchKeyKeyboardRestore = SwitchState.ON;  overrideKeyboardRestore = OverrideMode.FOLLOW_SYSTEM;
        switchKeyKeyboardReverse = SwitchState.ON;  overrideKeyboardReverse = OverrideMode.FOLLOW_SYSTEM;
        switchAltRightKR = SwitchState.ON;          overrideAltRightKR = OverrideMode.FOLLOW_SYSTEM;
        // 六、全局
        zuxKeyboardFuncEnabled = true;
        injected = false;
        injectError = "";
        oneVisionFeatureEnabled = true;
        // 七、日+ 区域
        verboseLevel = LogHelper.VerboseLevel.INFO;
        regionOverride = RegionProfile.DEFAULT;
        regionCustomValue = "";
        matchSystemTheme = true;
        nightMode = 0;
        dynamicColorEnabled = false;
        // 八、区域行
        keyboardScanCode = 0;
        rowInputMethodSwitch = true;
        rowLanguageSwitch = true;
        metaShortPressAction = MetaAction.DEFAULT;
        metaLongPressAction = MetaAction.DEFAULT;
        metaHoldAction = MetaAction.DEFAULT;
        krAltRightSwitch = true;
        aiAgent = AiAgent.DEFAULT;
        fileManager = FileManager.DEFAULT;
        aiSummaryEnabled = true;
        // 九、AOSP 辅助
        switchAospBounceKeys = SwitchState.OFF;   overrideAospBounceKeys = OverrideMode.AOSP;
        switchAospMouseKeys = SwitchState.OFF;    overrideAospMouseKeys = OverrideMode.AOSP;
        switchAospStickyKeys = SwitchState.OFF;   overrideAospStickyKeys = OverrideMode.AOSP;
        switchAospSlowKeys = SwitchState.OFF;     overrideAospSlowKeys = OverrideMode.AOSP;
        // 十、模
        templates = new ArrayList<>();
        // 十一、虚Fn 键（默认关闭
        fnKeyEnabled = false;
        fnLockEnabled = true;
        fnProfileKey = "";
        fnCustomProfiles = new java.util.LinkedHashMap<>();
        fnToastEnabled = true;
    }

    /**
     * 从系统实际状态检测每个快捷键是否受支持
     * 调用后会更新 switchXxx 字段ON/OFF/FORCED_ON/FORCED_OFF
     *
     * @param classLoader system_server ClassLoader，用于反射检测系统类
     */
    public void detectFromSystem(ClassLoader classLoader) {
        // -----------------------------------------------------------------
        // 检测策略：
        //   1. AOSP 原生支持的组合键 无条ON（所Android 设备通用
        //   2. ZUI 特有功能 反射检KSC 类，存在ON，不存在保持 FORCED_ON
        //   3. 基础标准键（Caps/PrintScreen 等）无条ON
        // -----------------------------------------------------------------

        // --- AOSP 原生快捷键（所Android 设备通用，不依赖 ZUI--
        // Win+D: L3 AOSP PhoneWindowManager type=1 goHome()
        switchWinD = SwitchState.ON;
        // Win+I: AOSP InputGestureManager 默认注册 type=7 launchSettings
        switchWinI = SwitchState.ON;
        // Win+M: L3 AOSP type=201 ovMinimizeFreeformGroup
        switchWinM = SwitchState.ON;
        // Win+N: L3 AOSP type=8 通知面板
        switchWinN = SwitchState.ON;
        // Win+↑↓: L3 AOSP type=53/52 窗口最大化/还原 (共用)
        switchWinUp = SwitchState.ON;
        // Meta 单按: L3 AOSP type=21 triggerShowAllApps（开始菜单）
        switchMetaSingle = SwitchState.ON;

        // --- ZUI 特有功能 尝试反射检---
        if (classLoader != null) {
            detectZuiFeatures(classLoader);
        }

        // --- 基础键（所有设备通用--
        switchCapsLock = SwitchState.ON;
        switchAltTab = SwitchState.ON;
        switchCtrlSpace = SwitchState.ON;
        switchCtrlEnter = SwitchState.ON;
        switchPrintScreenShort = SwitchState.ON;
        switchPrintScreenLong = SwitchState.ON;

        // --- 读取 Settings.System 实际开关---
        readSystemSwitches();

        // 标记已检测，避免重复
        systemDetected = true;
    }

    /** 是否已完成系统能力检*/
    private boolean systemDetected = false;

    public boolean isSystemDetected() { return systemDetected; }

    /**
     * 共享的 shortKey → Settings.System key 映射表
     * 供 readSystemSwitches / writeSystemSwitch / syncSwitchesFromSystem 共用
     */
    private static java.util.Map<String, String> getSwitchKeyMap() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("winD",        "keyboard_combo_win_d");
        map.put("winS",        "keyboard_combo_win_s");
        map.put("winA",        "keyboard_combo_win_a");
        map.put("winBack",     "keyboard_combo_win_back");
        map.put("winE",        "keyboard_combo_win_e");
        map.put("winI",        "keyboard_combo_win_i");
        map.put("winL",        "keyboard_combo_win_l");
        map.put("winM",        "keyboard_combo_win_m");
        map.put("winN",        "keyboard_combo_win_n");
        map.put("winP",        "keyboard_combo_win_p");
        map.put("winW",        "keyboard_combo_win_w");
        map.put("winNumber",   "keyboard_combo_win_number");
        map.put("winTab",      "keyboard_combo_win_tab");
        map.put("winLeft",     "keyboard_combo_lr_arrow");
        map.put("winUp",       "keyboard_combo_ud_arrow");
        map.put("ctrlLongPress","keyboard_combo_ctrl_3");
        map.put("ctrlShift",   "keyboard_combo_ctrl_shift");
        map.put("altShift",    "keyboard_combo_alt_shift");
        map.put("altTab",      "keyboard_combo_alt_tab");
        map.put("ctrlEnter",   "keyboard_combo_ctrl_enter");
        map.put("ctrlSpace",   "keyboard_combo_ctrl_space");
        return map;
    }

    /**
     * 读取 Settings.System keyboard_combo_* 的实际值，
     * 同步开关状态（读取成功→ON/OFF，失败→保持原值）
     * 公开方法，MainHook 每次加载时调用
     */
    public void readSystemSwitchesPublic() {
        readSystemSwitches();
        save();
    }

    private void readSystemSwitches() {
        android.content.ContentResolver cr = null;
        try {
            Object at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null);
            android.content.Context ctx = (android.content.Context)
                at.getClass().getMethod("getSystemContext").invoke(at);
            cr = ctx.getContentResolver();
            LogHelper.log(LogHelper.VerboseLevel.INFO, "Config: got ContentResolver for system switch reading");
        } catch (Throwable t) {
            LogHelper.log(LogHelper.VerboseLevel.ERROR, "Config: failed to get ContentResolver:", t.getMessage());
            return;
        }

        readSwitchStatesFromResolver(cr);
    }

    /** 从 ContentResolver 读取所有系统开关并更新 SwitchState */
    private void readSwitchStatesFromResolver(android.content.ContentResolver cr) {
        int readOk = 0, readFail = 0;
        java.util.Map<String, String> map = getSwitchKeyMap();

        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            String sysKey = e.getValue();
            try {
                java.lang.reflect.Field f = getClass().getField("switch" +
                    key.substring(0, 1).toUpperCase() + key.substring(1));
                int val = android.provider.Settings.System.getInt(cr, sysKey, -1);
                if (val == 1)      { f.set(this, SwitchState.ON);  readOk++; }
                else if (val == 0) { f.set(this, SwitchState.OFF); readOk++; }
                // -1 保持 detectFromSystem 设置的值，计入 readFail
                else { readFail++; }
            } catch (Throwable t) {
                readFail++;
            }
        }
        LogHelper.log(LogHelper.VerboseLevel.INFO, "Config: system switches read OK=",
            String.valueOf(readOk), " FAIL=", String.valueOf(readFail));
    }

    /**
     * 从系统同步所有 SwitchState（App 进程侧）
     * 使用传入的 Context 读取 Settings.System，不依赖 ActivityThread 反射。
     * GlobalFragment 在视图创建和下拉刷新时调用。
     *
     * @param ctx App 进程 Context（用于 ContentResolver）
     */
    public void syncSwitchesFromSystem(android.content.Context ctx) {
        if (ctx == null) return;
        readSwitchStatesFromResolver(ctx.getContentResolver());
    }

    /**
     * 将单SwitchState 写回 Settings.System，实现模ZUI 系统开关双向同步
     * 优先通过 ContentResolver 写入，失败则回退su -c 方式（需 Root）
     *
     * @param ctx       App Context（用ContentResolver 直写尝试
     * @param shortKey  快捷方式内部 key，如 "winD"
     * @param enabled   true=ON(写入1), false=OFF(写入0)
     * @return null 表示成功，否则返回错误信
     */
    public static String writeSystemSwitch(android.content.Context ctx, String shortKey, boolean enabled) {
        String sysKey = getSwitchKeyMap().get(shortKey);
        if (sysKey == null) return "Unknown shortcut key: " + shortKey;

        int val = enabled ? 1 : 0;
        String error = null;

        // 方式一：尝试通过 ContentResolver 直接写入（需WRITE_SETTINGS
        if (ctx != null) {
            try {
                android.provider.Settings.System.putInt(ctx.getContentResolver(), sysKey, val);
                return null;
            } catch (Throwable t) {
                error = t.getMessage();
            }
        }

        // 方式二：回退su -c settings put（以 system UID 执行
        try {
            String cmd = "settings put system " + sysKey + " " + val;
            Process su = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});

            java.io.BufferedReader errReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(su.getErrorStream()));
            StringBuilder errSb = new StringBuilder();
            String line;
            while ((line = errReader.readLine()) != null) errSb.append(line).append("\n");
            errReader.close();

            su.waitFor();
            if (su.exitValue() == 0) {
                return null;
            }
            String suErr = errSb.toString().trim();
            return "su exit=" + su.exitValue() +
                (suErr.isEmpty() ? "" : ": " + suErr) +
                (error != null ? " | ContentResolver: " + error : "");
        } catch (Throwable t) {
            return "su error: " + t.getMessage() +
                (error != null ? " | ContentResolver: " + error : "");
        }
    }

    /**
     * 尝试通过反射检ZUI 特有功能是否存在
     */
    private void detectZuiFeatures(ClassLoader cl) {
        // KeyboardShortcutController 中每type 对应的方法或常量
        // 如果能找到类本身，说明大多数 ZUI 功能都可
        try {
            cl.loadClass("com.zui.server.input.keyboard.key.policy.KeyboardShortcutController");
            // KSC 类存ZUI 框架完整，以下功能标记为 ON
            switchWinS = SwitchState.ON;
            switchWinA = SwitchState.ON;
            switchWinBack = SwitchState.ON;
            switchWinE = SwitchState.ON;
            switchWinL = SwitchState.ON;
            switchWinP = SwitchState.ON;
            switchWinW = SwitchState.ON;
            switchWinNumber = SwitchState.ON;
            switchWinTab = SwitchState.ON;
            switchWinLeft = SwitchState.ON;
            switchCtrlLongPress = SwitchState.ON;
            switchCtrlShift = SwitchState.ON;
            switchAltShift = SwitchState.ON;
            switchCtrlShiftT = SwitchState.ON;
            switchMetaShortRow = SwitchState.ON;
            switchMetaLongRow = SwitchState.ON;
            switchMetaHoldNonRow = SwitchState.ON;

            // ZUI 物理
            switchKeyMute = SwitchState.ON;
            switchKeyTouchpad = SwitchState.ON;
            switchKeySplitScreen = SwitchState.ON;
            switchKeySuperConnect = SwitchState.ON;
            switchKeyApp1 = SwitchState.ON;
            switchKeyApp2 = SwitchState.ON;
            switchKeySearch = SwitchState.ON;
            switchKeySettings = SwitchState.ON;
            switchKeyFnLock = SwitchState.ON;
            switchKeyBacklight = SwitchState.ON;
            switchKeyTpUp = SwitchState.ON;
            switchKeyScreenLock = SwitchState.ON;
            switchKeyKeyboardRestore = SwitchState.ON;
            switchKeyKeyboardReverse = SwitchState.ON;
            switchAltRightKR = SwitchState.ON;
        } catch (ClassNotFoundException e) {
            // KSC 类不存在 ZUI 设备，所ZUI 特有功能保持 FORCED_ON
        }
    }
}
