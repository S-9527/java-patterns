package demo.flyweight;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 享元池 —— 看起来像 Flyweight，<b>实际上就是一个 Map</b>。
 *
 * <p>这是本场景最重要的结论，写在类名里：<b>在 Java 里，"享元池"通常就是
 * {@code Map<K, V> + computeIfAbsent}，不需要任何专门的模式。</b>
 *
 * <p>GoF 把享元定义成一个模式，是因为它诞生于 1990 年代的 C++/Smalltalk ——
 * 那时候"共享对象"需要显式的工厂和池管理，还得考虑对象表会不会无限膨胀。
 * Java 有了 GC 和 {@code Map}，这些顾虑大部分消失了。
 *
 * <p><b>什么时候 Map 不够用</b>（真的需要手写享元）：
 * <ul>
 *   <li>池需要<b>淘汰策略</b>：LRU / LFU / TTL，{@code Map} 不提供</li>
 *   <li>池大小需要<b>硬上限</b>，否则 key 无界增长会 OOM</li>
 *   <li>需要池的<b>统计信息</b>（命中率、创建次数）作为业务指标</li>
 * </ul>
 *
 * <p>见 {@link BoundedMetaPool} —— 那是真正需要手写享元的场景。
 */
public final class ProductMetaFactory {

    private static final ProductMetaFactory SHARED = new ProductMetaFactory();

    private final Map<MetaKey, ProductMeta> pool = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private ProductMetaFactory() {
    }

    /** 全局池。真实项目里这大概是应用生命周期内的一个 bean。 */
    public static ProductMetaFactory shared() {
        return SHARED;
    }

    /**
     * 取或建。
     *
     * <p>{@code computeIfAbsent} 在并发下保证<b>同一 key 只有一个线程执行映射函数</b>，
     * 所以不存在"两个线程各建一个元数据对象"的情况。这正是享元要的语义。
     */
    public ProductMeta computeIfAbsent(MetaKey key, java.util.function.Function<MetaKey, ProductMeta> factory) {
        var cached = pool.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        return pool.computeIfAbsent(key, factory);
    }

    public ProductMeta get(MetaKey key) {
        return pool.get(key);
    }

    public int poolSize() {
        return pool.size();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public void clear() {
        pool.clear();
        hits.set(0);
        misses.set(0);
    }
}