package moe.lovefirefly.betterzuikey.Region;

/**
 * 区域配置文件。
 * 决定 ZUI 键盘快捷键中 ROW/中国/韩国 等差异化行为的分支走向。
 *
 * 背景：
 *   ZUI 通过 {@code ro.config.lgsi.region} (ROW) 和
 *   {@code ro.config.lgsi.countrycode} (KR) 两个系统属性
 *   控制快捷键的区域差异化行为：
 *   - ROW: Ctrl+Shift/Alt+Shift 切输入法/语言、Meta 短按切语言/长按助手
 *   - 中国: Meta 按住连续切语言、联想乐语音 AI 代理
 *   - 韩国: Alt_RIGHT(58) 切换韩语输入法
 */
public enum RegionProfile {
    /** 不干预，使用系统原生区域设定 */
    DEFAULT,

    /** 强制中国区（非 ROW） */
    CHINA,

    /** 强制海外区（ROW: Rest of World） */
    ROW,

    /** 强制韩国区 */
    KOREA,

    /** 自定义区域值（用户输入的任意字符串，如 "TW"、"JP" 等） */
    CUSTOM;

    /**
     * 此 profile 是否应被 isRowProduct 返回 true
     */
    public boolean isRow() {
        return this == ROW;
    }

    /**
     * 此 profile 是否应被 isKrProduct 返回 true
     */
    public boolean isKr() {
        return this == KOREA;
    }

    /**
     * 此 profile 是否应被 isChinaProduct 返回 true
     */
    public boolean isChina() {
        return this == CHINA;
    }
}
