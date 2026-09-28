package moe.lovefirefly.betterzuikey.Config;

/**
 * 单项覆写 — 应用模板中的一条规则。
 *
 * <p>所有字段为 null 表示「继承全局」。除了通用的开关 / 覆写模式，这里还承载
 * **卡片专属档位的独立取值**（「映射到…」的目标、执行命令、智能键三档）——
 * 否则模板里根本表达不出全局页那几个特有档位。
 */
public class PerKeyOverride {
    /** null = 继承全局 SwitchState */
    public Config.SwitchState switchState;
    /** null = 继承全局 OverrideMode */
    public Config.OverrideMode overrideMode;

    // ── metaSingle：「映射到…」────────────────────────────────────────
    /** null = 继承；true = 本模板用「映射到…」 */
    public Boolean useMap;
    /**
     * 本模板**自己的**映射目标（格式同 {@link Config#metaSingleMap}）；null / 空 = 用全局目标。
     * 模板之间、以及和全局之间互不影响。
     */
    public String mapTarget;

    // ── winLongPress / keyApp1 / keyApp2：执行命令 ────────────────────
    /** true = 执行命令（覆盖 overrideMode）；null = 继承 */
    public Boolean useCommand;
    public String command;
    public Boolean commandRoot;
    public Boolean commandSingleton;
    public Integer commandTimeoutMin;

    // ── keyApp1 / keyApp2：三档（跟随系统 / 忽略 / 执行命令…）─────────
    /** null = 继承全局 AppKeyMode */
    public Config.AppKeyMode appKeyMode;

    public PerKeyOverride() {}

    public PerKeyOverride(Config.SwitchState s, Config.OverrideMode m) {
        this.switchState = s;
        this.overrideMode = m;
    }

    /** 是否完全继承全局（无任何覆写） */
    public boolean isInherit() {
        return switchState == null
                && overrideMode == null
                && useMap == null
                && mapTarget == null
                && useCommand == null
                && command == null
                && commandRoot == null
                && commandSingleton == null
                && commandTimeoutMin == null
                && appKeyMode == null;
    }

    /**
     * 完整复制一条覆写。
     *
     * <p>新增字段时**务必同步这里**：模板复制走的就是本方法，漏一个字段
     * 就会在复制后静默丢掉该卡的设置。
     */
    public PerKeyOverride copy() {
        PerKeyOverride o = new PerKeyOverride();
        o.switchState = switchState;
        o.overrideMode = overrideMode;
        o.useMap = useMap;
        o.mapTarget = mapTarget;
        o.useCommand = useCommand;
        o.command = command;
        o.commandRoot = commandRoot;
        o.commandSingleton = commandSingleton;
        o.commandTimeoutMin = commandTimeoutMin;
        o.appKeyMode = appKeyMode;
        return o;
    }
}
