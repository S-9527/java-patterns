package demo.facade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 待结算的购物车。各服务共享的数据结构。 */
public record CartDraft(String customerId, List<Item> items) {

    public CartDraft {
        items = List.copyOf(items);
    }

    public int itemCount() {
        return items.stream().mapToInt(Item::quantity).sum();
    }

    public record Item(String sku, int quantity, BigDecimal unitPrice) {

        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    /** 演示用的内存库存表。 */
    public static final Map<String, Integer> DEFAULT_STOCK =
            Map.of("SKU-1001", 100, "SKU-2044", 10, "SKU-3310", 0);
}