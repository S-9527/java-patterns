package demo.config;

/** 注入式的同类服务 —— 依赖在构造器签名里，一眼可见。 */
public record InjectedService(String name, ConfigSource config) {

    public int timeoutMs() {
        return config.get(new ConfigKey("timeout.ms"))
                .map(ConfigValue::raw)
                .map(Integer::parseInt)
                .orElse(1000);
    }

    public String describe() {
        return "%s 用注入配置，超时 %dms".formatted(name, timeoutMs());
    }
}