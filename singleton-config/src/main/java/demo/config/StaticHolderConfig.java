package demo.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>更好的单例</b>：静态持有者（Holder idiom）。
 *
 * <p>比 {@link StaticConfig} 更短、更快、不需要 {@code volatile}。但它<b>没有解决任何
 * 单例本身的根本问题</b> —— 隐藏依赖、难测试、无法注入，一个都没少。
 *
 * <p>这个类存在的意义是做对照：如果有人说"我们不用枚举改用 holder 就没问题了"，
 * 你可以把这段代码给他看。写法进步了，问题原封不动。
 *
 * <p><b>它和枚举版唯一的真实差别</b>是延迟到首次访问才初始化，且可以用构造器传参。
 *
 * <p>不做泛型 —— 泛型 Holder 会因为"Java 不允许泛型静态字段"而退化，
 * 教科书上那种漂亮的写法在实际项目里往往不成立。留一个非泛型版本更诚实。
 */
public final class StaticHolderConfig {

    private final Map<ConfigKey, String> values = new ConcurrentHashMap<>();

    private StaticHolderConfig() {
    }

    /** Holder 类在首次访问 {@link #getInstance()} 时才被加载，JVM 保证线程安全。 */
    private static final class Holder {
        static final StaticHolderConfig INSTANCE = new StaticHolderConfig();
    }

    public static StaticHolderConfig getInstance() {
        return Holder.INSTANCE;
    }

    public Optional<String> get(ConfigKey key) {
        return Optional.ofNullable(values.get(key));
    }

    public String getOrDefault(ConfigKey key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public void put(ConfigKey key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    public Set<ConfigKey> keys() {
        return Set.copyOf(values.keySet());
    }
}
