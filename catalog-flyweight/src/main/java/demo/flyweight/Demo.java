package demo.flyweight;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 享元：一个<b>看起来过度设计</b>的模式。
 *
 * <p>核心结论写在 {@link ProductMetaFactory} 的类注释里：<b>在 Java 里享元池通常就是
 * {@code Map + computeIfAbsent}</b>。除非你需要淘汰策略或硬上限
 * （见 {@link BoundedMetaPool}），否则自己造一套享元框架是纯粹的成本。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        ProductMetaFactory.shared().clear();

        heading("10 万 SKU，共享 20 种元数据");
        var products = buildCatalog(100_000, 20);

        var metaObjects = new HashSet<ProductMeta>();
        products.forEach(p -> metaObjects.add(p.meta()));

        out("  SKU 数量        %d".formatted(products.size()));
        out("  实际元数据对象   %d".formatted(metaObjects.size()));
        out("  命中 %d 次，未命中 %d 次"
                .formatted(ProductMetaFactory.shared().hitCount(),
                           ProductMetaFactory.shared().missCount()));
        out();
        out("  不共享的话内存里会有 %d 份一模一样的元数据。".formatted(products.size()));
        out("  共享后只有 %d 份 —— 这就是享元省下来的东西。".formatted(metaObjects.size()));

        heading("⚠ 但这就是全部吗");
        out("  上面的实现是：");
        out();
        out("      Map<MetaKey, ProductMeta> pool = new ConcurrentHashMap<>();");
        out("      pool.computeIfAbsent(key, k -> new ProductMeta(...));");
        out();
        out("  两行。没有工厂类，没有池接口，没有 Flyweight 基类。");
        out("  GoF 把它叫一个模式，是因为 1990 年代的语言没有 Map 的 computeIfAbsent。");
        out("  换个说法：这个模式在 Java 里已经「内联」到语言特性里了。");

        heading("什么时候 Map 不够用");
        out("  1. 池需要淘汰策略（LRU / LFU / TTL）");
        out("  2. 池大小需要硬上限，否则 key 无界增长就是 OOM");
        out("  3. 需要命中率统计作为业务指标");
        out();
        out("  这时候才值得写一个池 —— 见 BoundedMetaPool。");

        heading("带上限的池");
        demoBoundedPool();

        heading("⚠ 什么时候享元根本就是错的");
        demoWhenFlyweightIsWrong();

        heading("共享的前提：不可变");
        out("  ProductMeta 是不可变 record。");
        out("  如果它是可变的，一个线程改了分类，所有引用它的 SKU 都会跟着变。");
        out("  「共享」和「不可变」是绑定的 —— 这是享元唯一的硬性前提。");
        out();
        out("  本项目的元数据共享看起来省了很多内存，但说实话：");
        out("  %d 个 ProductMeta 对象本来也就几 MB。".formatted(metaObjects.size()));
        out("  真实系统里几 MB 的内存从来不值得引入一个模式。");
        out("  值得的是「100 万份副本」和「20 份」的差别。");

        System.out.println();
    }

    private static void demoBoundedPool() {
        var pool = new BoundedMetaPool(5);

        // 装 8 种不同的元数据，池上限 5。
        // ⚠ 这里用 for 循环而不是 lambda：i 每次迭代都变，
        // 不能被 lambda 捕获（"must be final or effectively final"）。
        // 需要闭包的话得先把值复制到一个 final 局部变量里。
        for (int i = 0; i < 8; i++) {
            var category = "分类" + i;      // effectively final
            var brand = "品牌" + i;
            var price = BigDecimal.valueOf(10L + i);
            var version = i;
            pool.get(new MetaKey(category, brand),
                    k -> new ProductMeta(k.category(), k.brand(), "CNY", price, version));
        }

        out("  放入 8 种，池上限 5");
        out("  当前大小 %d，淘汰 %d 次".formatted(pool.size(), pool.evictionCount()));
        out("  命中 %d，未命中 %d，命中率 %.0f%%"
                .formatted(pool.hitCount(), pool.missCount(), pool.hitRate() * 100));
        out();

        // 重复访问一个还在池里的 key
        var survivor = new MetaKey("分类7", "品牌7");
        pool.get(survivor, k -> new ProductMeta("x", "y", "CNY", BigDecimal.ONE, 0));
        out("  再查一次最新的 key → 命中 %d 次".formatted(pool.hitCount()));

        out();
        out("  代价：LRU 要在每次访问时改顺序，引入锁竞争和内存开销。");
        out("  computeIfAbsent 一行搞定的事，这里写了 60 行。");
        out("  而且本实现只是「最终一致」，并发下可能短暂超限 ——");
        out("  真到生产环境，用 Caffeine 之类的现成池，别自己写。");
    }

    private static void demoWhenFlyweightIsWrong() {
        out("  1. 元数据本来就少，重复也不多 —— 直接 new，省事");
        out("     本项目 20 种元数据就算不共享也才几 MB。");
        out();
        out("  2. 对象不是共享安全的（可变）—— 共享 = 所有人一起被改");
        out();
        out("  3. 「共享」这件事本身没有业务含义");
        out("     享元适合：地区表、币种表、枚举字典这类「天然全局」的只读数据");
        out("     不适合：订单、商品这类「本来就应该各有各的」数据");
        out();
        out("  4. 淘汰逻辑比省下的内存还复杂");
        out("     池里的逻辑比直接 new 一份还容易出错，那就别共享。");
    }

    private static List<Product> buildCatalog(int skuCount, int metaVariants) {
        var result = new ArrayList<Product>(skuCount);
        var brands = List.of("Acme", "Globex", "Initech", "Umbrella", "Soylent",
                "Stark", "Wayne", "Cyberdyne", "Tyrell", "Weyland");
        var categories = List.of("电子", "图书", "服饰", "食品");

        // metaVariants 种「分类 × 品牌」组合。
        // ⚠ 这里用 i % metaVariants 而不是两重取模 —— 两重取模会塌成 4 种
        //   （第一版就是这么写的，标题说 20 种实际只生成 4 种）
        for (int i = 0; i < skuCount; i++) {
            var variant = i % metaVariants;
            var category = categories.get(variant % categories.size());
            var brand = brands.get(variant % brands.size());
            result.add(Product.of("SKU-%06d".formatted(i),
                    category, brand,
                    BigDecimal.valueOf(10 + (i % 1000)),
                    i % 50,
                    "WH-" + (i % 5)));
        }
        return result;
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}