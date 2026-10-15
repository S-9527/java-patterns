package demo.flyweight;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 商品元数据 —— <b>内蕴状态（intrinsic）</b>，享元要共享的部分。
 *
 * <p>做成不可变 record。这是享元成立的前提：<b>共享的对象必须不可变</b>，
 * 否则一个线程改了分类，所有引用它的 SKU 都跟着变。
 *
 * <p>这个类还刻意做成了值语义（用 {@code equals}/{@code hashCode}）而不是
 * 引用语义 —— 这样"哪些 SKU 共享了同一个元数据对象"可以直接用相等性判断，
 * 测试里能断言 {@code ==}（同一实例）。
 */
public record ProductMeta(String category, String brand, String currencyCode,
                          BigDecimal listPrice, long version) implements Comparable<ProductMeta> {

    public ProductMeta {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("分类不能为空");
        }
        if (brand == null || brand.isBlank()) {
            throw new IllegalArgumentException("品牌不能为空");
        }
        // ⚠ 只查长度 == 3 是不够的："RMB" 也是 3 个字符，但不是有效的 ISO 币种代码。
        // 真正校验要用 java.util.Currency —— 它认的是 ISO 4217（人民币是 CNY 不是 RMB）。
        if (currencyCode == null || currencyCode.length() != 3) {
            throw new IllegalArgumentException("币种必须是 3 位代码：" + currencyCode);
        }
        try {
            java.util.Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "无效的 ISO 4217 币种代码：%s（人民币是 CNY，不是 RMB）".formatted(currencyCode));
        }
        listPrice = Objects.requireNonNull(listPrice).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public int compareTo(ProductMeta o) {
        return Long.compare(version, o.version);
    }

    /** 展示用摘要。 */
    public String describe() {
        return "%s/%s %s %s".formatted(brand, category, currencyCode, listPrice);
    }
}