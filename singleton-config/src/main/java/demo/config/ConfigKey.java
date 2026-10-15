package demo.config;

/** 配置键。 */
public record ConfigKey(String name) {
    public ConfigKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("配置键不能为空");
        }
    }
}
