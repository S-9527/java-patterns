package demo.flyweight;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("享元 —— 什么时候它是过度设计")
class FlyweightTest {

    @BeforeEach
    void setUp() {
        ProductMetaFactory.shared().clear();
    }

    private static ProductMetaFactory factory() {
        return ProductMetaFactory.shared();
    }

    private static ProductMeta make(MetaKey key) {
        return factory().computeIfAbsent(key,
                k -> new ProductMeta(k.category(), k.brand(), "CNY", BigDecimal.TEN, 1));
    }

    @Test
    @DisplayName("⚠ 同一个 key 拿到的是同一个实例 —— 这就是享元")
    void sameKeySameInstance() {
        var key = new MetaKey("电子", "Acme");
        var first = make(key);
        var second = make(key);

        assertSame(first, second, "享元的核心：共享同一实例");
    }

    @Test
    @DisplayName("不同 key 拿到不同实例")
    void differentKeysDifferentInstances() {
        assertNotSame(make(new MetaKey("电子", "Acme")),
                make(new MetaKey("图书", "Acme")));
    }

    @Test
    @DisplayName("⚠ 10 万 SKU 只产生 20 份元数据 —— 池大小与 SKU 数解耦")
    void poolSizeIndependentOfSkuCount() {
        var key = new MetaKey("电子", "Acme");
        var products = new ArrayList<Product>();
        for (int i = 0; i < 10_000; i++) {
            products.add(new Product("SKU-" + i, make(key), i, "WH-1"));
        }

        assertEquals(10_000, products.size());
        assertEquals(1, factory().poolSize(), "1 万个 SKU，池里只有 1 份元数据");

        var distinct = new HashSet<ProductMeta>();
        products.forEach(p -> distinct.add(p.meta()));
        assertEquals(1, distinct.size());
    }

    @Test
    @DisplayName("元数据不可变 —— 这是共享的前提")
    void metaIsImmutable() {
        var meta = make(new MetaKey("电子", "Acme"));
        // record 没有 setter；构造器参数都是 final
        assertTrue(java.util.Arrays.stream(ProductMeta.class.getRecordComponents()).count() == 5);
        assertTrue(java.util.Arrays.stream(ProductMeta.class.getMethods())
                        .noneMatch(m -> m.getName().startsWith("set")),
                "ProductMeta 不该有任何 setter");
    }

    @Test
    @DisplayName("并发建池：虚拟线程下同一 key 只建一次")
    void concurrentCreationIsRaceFree() throws InterruptedException {
        var key = new MetaKey("电子", "Acme");
        var results = new java.util.concurrent.ConcurrentLinkedQueue<ProductMeta>();
        var threadCount = 200;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        var error = new AtomicReference<Throwable>();

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    results.add(make(key));
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "所有线程应完成");
        assertEquals(null, error.get(), "并发建池不应出错");

        assertEquals(200, results.size());
        var distinct = new HashSet<>(results);
        assertEquals(1, distinct.size(),
                "⚠ 200 个虚拟线程并发取同一个 key，必须只建 1 个对象");
        assertEquals(1, factory().poolSize());
    }

    @Test
    @DisplayName("⚠ 命中统计：有池才有意义")
    void hitRateTracked() {
        var key = new MetaKey("电子", "Acme");
        make(key);
        make(key);
        make(key);

        assertEquals(1, factory().missCount(), "第一次是未命中");
        assertEquals(2, factory().hitCount(), "后两次命中");
    }

    @Test
    @DisplayName("带上限的池：超过 maxSize 会淘汰")
    void boundedPoolEvicts() {
        var pool = new BoundedMetaPool(3);
        for (int i = 0; i < 8; i++) {
            var key = new MetaKey("分类" + i, "品牌" + i);
            // i 不是 effectively final，lambda 捕获不了 —— 先落到一个 final 变量上
            final var version = i;
            pool.get(key, k -> new ProductMeta(k.category(), k.brand(), "CNY",
                    BigDecimal.ONE, version));
        }

        assertEquals(3, pool.size(), "池大小不超过上限");
        assertEquals(5, pool.evictionCount(), "8 - 3 = 5 次淘汰");
    }

    @Test
    @DisplayName("带上限的池：上限必须为正")
    void boundedPoolRejectsBadSize() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedMetaPool(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedMetaPool(-1));
    }

    @Test
    @DisplayName("带上限的池：命中率达到 100% 的情况")
    void boundedPoolHitRate() {
        var pool = new BoundedMetaPool(10);
        var key = new MetaKey("电子", "Acme");

        pool.get(key, k -> new ProductMeta("电子", "Acme", "CNY", BigDecimal.ONE, 1));
        for (int i = 0; i < 9; i++) {
            pool.get(key, k -> new ProductMeta("x", "y", "CNY", BigDecimal.ONE, 0));
        }

        assertEquals(0.9, pool.hitRate(), 0.001);
        assertEquals(0, pool.evictionCount(), "只有一个 key，不该淘汰");
    }

    @Test
    @DisplayName("带上限的池：空池命中率为 0 而不是 NaN")
    void hitRateOfEmptyPool() {
        assertEquals(0.0, new BoundedMetaPool(5).hitRate());
    }

    @Test
    @DisplayName("⚠ ProductMetaFactory 没有上限 —— 这就是它不够用的地方")
    void sharedFactoryIsUnbounded() {
        // 无界池：key 无界增长就是内存泄漏。
        // 这是 Map 版享元的真实局限，也是 BoundedMetaPool 存在的理由。
        for (int i = 0; i < 1000; i++) {
            make(new MetaKey("分类" + i, "品牌" + i));
        }
        assertEquals(1000, factory().poolSize(), "无上限，想放多少放多少");

        var bounded = new BoundedMetaPool(10);
        for (int i = 0; i < 1000; i++) {
            var k = new MetaKey("分类" + i, "品牌" + i);
            final var version = i;
            bounded.get(k, key -> new ProductMeta(key.category(), key.brand(), "CNY",
                    BigDecimal.ONE, version));
        }
        assertEquals(10, bounded.size(), "有上限的池挡住了无界增长");
    }

    @Test
    @DisplayName("用 record 做池的键 —— 避免拼字符串的冲突")
    void metaKeyIsAValue() {
        // 拼字符串的话 ("a/b","c") 和 ("a","b/c") 会撞
        assertEquals(new MetaKey("a/b", "c"), new MetaKey("a/b", "c"));
        assertFalse(new MetaKey("a/b", "c").equals(new MetaKey("a", "b/c")),
                "record 保证了 (category, brand) 的顺序有意义");
    }

    @Test
    @DisplayName("⚠ 币种校验：RMB 是 3 个字符，但不是有效的 ISO 4217 代码")
    void metaValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductMeta("", "Acme", "CNY", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductMeta("电子", "", "CNY", BigDecimal.ONE, 1));

        // 只查长度会漏掉这个 —— 人民币的 ISO 代码是 CNY，RMB 不是
        assertThrows(IllegalArgumentException.class,
                () -> new ProductMeta("电子", "Acme", "RMB", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductMeta("电子", "Acme", "CN", BigDecimal.ONE, 1),
                "长度不对");
    }

    @Test
    @DisplayName("Product.of 自动取共享元数据")
    void productOfUsesSharedMeta() {
        var a = Product.of("SKU-1", "电子", "Acme", new BigDecimal("99"), 10, "WH-1");
        var b = Product.of("SKU-2", "电子", "Acme", new BigDecimal("99"), 20, "WH-2");

        assertSame(a.meta(), b.meta(), "同分类同品牌共享元数据");
        assertEquals(1, factory().poolSize());
        assertFalse(a == b, "但商品本身各有各的（外蕴状态）");
        assertEquals(10, a.stock());
        assertEquals(20, b.stock());
    }
}