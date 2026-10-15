package demo.flyweight;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>真正需要手写享元的场景</b>：池有硬上限和淘汰策略。
 *
 * <p>{@link ProductMetaFactory} 没上限，key 无界增长就是内存泄漏。
 * 真实系统里元数据可能有几十万种组合（分类 × 品牌 × 币种 × 价格档位），
 * 必须有淘汰。
 *
 * <p>这里实现一个<b>带 LRU 上限的享元池</b>。它比 {@code computeIfAbsent} 多做了三件事：
 * <ol>
 *   <li>硬上限：超过 {@code maxSize} 就淘汰最久未用的</li>
 *   <li>命中率统计：命中率和创建次数作为业务指标</li>
 *   <li>并发安全：用 {@link ConcurrentHashMap} + 访问队列</li>
 * </ol>
 *
 * <p><b>注意这里的代价</b>：LRU 需要在每次访问时改顺序，这引入了锁竞争和内存开销。
 * {@code computeIfAbsent} 一行代码就搞定的事，这里写了 60 行。
 *
 * <p><b>判据</b>：没有淘汰需求就别用这个。
 */
public final class BoundedMetaPool {

    private final int maxSize;
    private final Map<MetaKey, ProductMeta> pool = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<MetaKey> accessOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public BoundedMetaPool(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("池上限必须为正：" + maxSize);
        }
        this.maxSize = maxSize;
    }

    /**
     * 取或建，超上限则淘汰 LRU。
     *
     * <p><b>本实现是"最终一致"而非严格正确</b>：并发下可能短暂超出 maxSize，
     * 淘汰也不是严格 LRU（队列是近似的）。生产实现要更严谨，
     * 通常改用 Caffeine 之类的库 —— <b>但这恰恰说明了：真到了需要的时候，
     * 别自己写，用现成的池</b>。
     */
    public ProductMeta get(MetaKey key, java.util.function.Function<MetaKey, ProductMeta> factory) {
        var cached = pool.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            accessOrder.remove(key);       // 标记为最近使用
            accessOrder.add(key);
            return cached;
        }

        misses.incrementAndGet();
        var created = factory.apply(key);
        pool.put(key, created);
        accessOrder.add(key);
        evictIfNeeded();
        return created;
    }

    private void evictIfNeeded() {
        while (pool.size() > maxSize) {
            var oldest = accessOrder.poll();
            if (oldest == null) {
                return;
            }
            if (pool.remove(oldest) != null) {
                evictions.incrementAndGet();
            }
        }
    }

    public int size() {
        return pool.size();
    }

    public int maxSize() {
        return maxSize;
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public long evictionCount() {
        return evictions.get();
    }

    /** 命中率。没命中过返回 0。 */
    public double hitRate() {
        var total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }
}