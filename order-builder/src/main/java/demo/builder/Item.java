package demo.builder;

import java.math.BigDecimal;
import java.util.Objects;

/** 商品行项目。 */
public record Item(String name, BigDecimal unitPrice, int quantity) {
    public Item {
        Objects.requireNonNull(name, "商品名不能为空");
        Objects.requireNonNull(unitPrice, "单价不能为空");
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须为正：" + quantity);
        }
    }
}
