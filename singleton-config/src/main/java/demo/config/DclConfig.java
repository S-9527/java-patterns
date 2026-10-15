package demo.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>并发最弱的单例</b>：双重检查锁（volatile DCL）。
 *
 * <p>和 {@link StaticHolderConfig} 解决的问题一样，写法更啰嗦。
 * 本项目保留它是为了能用一个<b>真实测试</b>断言"DCL 是对的"：
 * 200 个虚拟线程抢初始化，构造器只跑 1 次。
 *
 * <p>这个断言有价值，因为这是"单例三写法"里唯一一个有并发风险的 ——
 * volatile 的位置放错（放在局部变量而不是字段上）就退化成有问题的版本。
 * 这里把 volatile 挪到字段上的写法会被测试锁定。
 *
 * <p><b>但请注意</b>：能正确实现"只初始化一次"，和"该不该用单例"是两个问题。
 * 这个测试证明的是前者。
 */
public final class DclConfig {

    /** volatile 是 DCL 成立的全部关键。没有它，写线程可能暴露一个"半构造"的实例。 */
    private static volatile DclConfig instance;

    private final Map<ConfigKey, String> values = new ConcurrentHashMap<>();
    private final AtomicInteger initCount = new AtomicInteger();

    private DclConfig() {
        // 构造器只应该被调用一次。任何测试断言都用这个计数器。
        initCount.incrementAndGet();
    }

    public static DclConfig getInstance() {
        // 第一次检查：快速路径，绝大多数调用直接返回
        if (instance == null) {
            // 只有真正实例化时才同步
            synchronized (DclConfig.class) {
                // 第二次检查：防止"两个线程都通过了第一次检查"
                if (instance == null) {
                    instance = new DclConfig();
                }
            }
        }
        return instance;
    }

    /** 只给测试。真实项目不该有这个钩子。 */
    public static void resetForTest() {
        instance = null;
    }

    public int initCount() {
        return initCount.get();
    }

    public Optional<String> get(ConfigKey key) {
        return Optional.ofNullable(values.get(key));
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