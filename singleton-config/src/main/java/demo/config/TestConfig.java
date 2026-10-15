package demo.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 一个测试专用的内存配置 —— 注入式的好处是能这么做。 */
public final class TestConfig implements ConfigSource {

    private final Map<ConfigKey, ConfigValue> values = new ConcurrentHashMap<>();

    public TestConfig put(String key, String value) {
        values.put(new ConfigKey(key), new ConfigValue(value, "test"));
        return this;
    }

    @Override
    public Set<ConfigKey> keys() {
        return Set.copyOf(values.keySet());
    }

    @Override
    public Optional<ConfigValue> get(ConfigKey key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void set(ConfigKey key, ConfigValue value) {
        values.put(key, value);
    }
}