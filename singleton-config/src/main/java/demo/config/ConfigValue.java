package demo.config;

/** 配置取值。 */
public record ConfigValue(String raw, String source) {
    public String toString() {
        return "%s (from %s)".formatted(raw, source);
    }
}
