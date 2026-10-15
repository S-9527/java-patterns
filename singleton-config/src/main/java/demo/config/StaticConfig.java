package demo.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>反面教材</b>：全局配置只用枚举单例。
 *
 * <p>这是 GoF 演示单例最常用的写法，也是<b>最容易被滥用的写法</b>。
 * 本项目写它出来是为了把它的代价摊开讲，不是推荐用它。
 *
 * <p>三个问题，在 {@code SingletonCostTest} 里都有对应断言：
 * <ol>
 *   <li><b>隐藏依赖</b> —— 任何方法里 {@code Config.INSTANCE.get(...)} 都能伸手拿，
 *       调用方完全看不出这个类依赖配置</li>
 *   <li><b>难测试</b> —— 测试之间共享同一份状态，改一个测试会影响另一个，
 *       而且顺序依赖不可控</li>
 *   <li><b>无法注入</b> —— 参数化的测试（比如跑 3 组不同配置）做不了，
 *       因为全局只有一份</li>
 * </ol>
 *
 * <p><b>更好但同样有问题的写法</b>见 {@link StaticHolderConfig} 和 {@link DclConfig} ——
 * 它们更短、更快，但上面三个问题一个都没解决。
 */
public enum StaticConfig implements ConfigSource {

    INSTANCE;

    private final Map<ConfigKey, ConfigValue> values = new java.util.concurrent.ConcurrentHashMap<>();

    /** 枚举单例不能用构造器传参 —— 这是它与生俱来的限制。 */
    StaticConfig() {
        // 假装从系统属性加载
        values.put(new ConfigKey("app.name"), new ConfigValue("pattern-scenarios", "systemProperty"));
        values.put(new ConfigKey("app.region"), new ConfigValue("cn-hangzhou", "systemProperty"));
        values.put(new ConfigKey("timeout.ms"), new ConfigValue("3000", "default"));
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

    /** 只给测试用 —— <b>真实项目不该有这种钩子</b>，它的存在本身就是味道。 */
    public void reset() {
        values.clear();
        values.put(new ConfigKey("app.name"), new ConfigValue("pattern-scenarios", "systemProperty"));
        values.put(new ConfigKey("app.region"), new ConfigValue("cn-hangzhou", "systemProperty"));
        values.put(new ConfigKey("timeout.ms"), new ConfigValue("3000", "default"));
    }
}