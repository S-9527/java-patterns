package demo.flyweight;

import java.math.BigDecimal;

/**
 * 商品 —— <b>外蕴状态（extrinsic）</b>，每个 SKU 各有一份。
 *
 * <p>关键设计：它<b>持有</b> {@link ProductMeta} 的引用，而不是复制一份元数据字段。
 *
 * <p>这就是享元的全部机制：内蕴状态被提取出来共享，外蕴状态留在各自对象里。
 */
public record Product(String sku, ProductMeta meta, int stock, String warehouse) {

    public Product {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU 不能为空");
        }
        meta = java.util.Objects.requireNonNull(meta, "元数据不能为空");
    }

    /** 创建一个商品，自动从工厂取共享的元数据。 */
    public static Product of(String sku, String category, String brand,
                             BigDecimal price, int stock, String warehouse) {
        var meta = ProductMetaFactory.shared().computeIfAbsent(
                new MetaKey(category, brand), key -> new ProductMeta(
                        key.category(), key.brand(), "CNY", price, System.nanoTime()));
        return new Product(sku, meta, stock, warehouse);
    }

    public String describe() {
        return "%s → %s (库存 %d)".formatted(sku, meta.describe(), stock);
    }
}