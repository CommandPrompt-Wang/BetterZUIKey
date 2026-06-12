package moe.lovefirefly.betterzuikey.Config;

/**
 * 单项覆写 — 应用模板中的一条规则。
 * 两个字段均为 null 表示"继承全局"。
 */
public class PerKeyOverride {
    /** null = 继承全局 SwitchState */
    public Config.SwitchState switchState;
    /** null = 继承全局 OverrideMode */
    public Config.OverrideMode overrideMode;

    public PerKeyOverride() {}

    public PerKeyOverride(Config.SwitchState s, Config.OverrideMode m) {
        this.switchState = s;
        this.overrideMode = m;
    }

    /** 是否完全继承全局（无任何覆写） */
    public boolean isInherit() {
        return switchState == null && overrideMode == null;
    }
}
