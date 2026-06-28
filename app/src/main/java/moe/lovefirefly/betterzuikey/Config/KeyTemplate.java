package moe.lovefirefly.betterzuikey.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快捷键模板 — 一组覆写规则，可应用到多个应用。
 *
 * 只存 diff（覆写项），未列出的键走全局默认。
 * JSON 序列化由 Gson 自动处理。
 */
public class KeyTemplate {
    /** 不可变唯一 ID，防止重名。创建时由 UUID 生成，之后不改。 */
    public String id;
    /** 模板名称，如 "游戏模式"、"视频模式" */
    public String name;
    /** 此模板是否启用 */
    public boolean enabled = true;
    /** 应用到此列表中的所有包名 */
    public List<String> packages = new ArrayList<>();

    /**
     * 覆写映射。
     * key: 快捷键标识（如 "winD", "ctrlSpace"）,
     * value: PerKeyOverride
     */
    public Map<String, PerKeyOverride> overrides = new LinkedHashMap<>();

    public KeyTemplate() {
        this.id = java.util.UUID.randomUUID().toString();
    }

    public KeyTemplate(String name) {
        this.id = java.util.UUID.randomUUID().toString();
        this.name = name;
    }

    /** 此模板是否匹配给定包名 */
    public boolean matches(String packageName) {
        return enabled && packages != null && packages.contains(packageName);
    }

    public PerKeyOverride get(String key) {
        return overrides.get(key);
    }

    public void put(String key, PerKeyOverride override) {
        if (override == null || override.isInherit()) {
            overrides.remove(key);
        } else {
            overrides.put(key, override);
        }
    }
}

