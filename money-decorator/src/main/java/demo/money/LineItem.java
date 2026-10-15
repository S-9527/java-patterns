package demo.money;

/** 购物车里的一行：某个 SKU 买多少个、单价多少。不可变。 */
public record LineItem(String sku, int quantity, Money unitPrice) {

    public LineItem {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU 不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须为正数：%s 的数量是 %d".formatted(sku, quantity));
        }
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("单价不能为负：%s 单价 %s".formatted(sku, unitPrice));
        }
    }

    public Money lineTotal() {
        return unitPrice.times(java.math.BigDecimal.valueOf(quantity));
    }
}