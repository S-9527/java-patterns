package demo.builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * 伸缩构造器（Telescoping Constructor）—— <b>不用建造者</b>的写法 1。
 *
 * <p>10 个参数，6 个 {@code null}。调用方看不出哪些字段是必填的。
 */
public final class TelescopingOrder {

    private final String id;
    private final String customerName;
    private final List<Item> items;
    private final BigDecimal total;
    private final OrderStatus status;
    private final String couponCode;
    private final String shippingAddress;
    private final String notes;
    private final List<String> tags;
    private final OrderPriority priority;

    public TelescopingOrder(String id, String customerName, List<Item> items,
                            BigDecimal total, OrderStatus status) {
        this(id, customerName, items, total, status,
                null, null, null, null, null);
    }

    public TelescopingOrder(String id, String customerName, List<Item> items,
                            BigDecimal total, OrderStatus status,
                            String couponCode, String shippingAddress,
                            String notes, List<String> tags,
                            OrderPriority priority) {
        this.id = id;
        this.customerName = customerName;
        this.items = items;
        this.total = total;
        this.status = status;
        this.couponCode = couponCode;
        this.shippingAddress = shippingAddress;
        this.notes = notes;
        this.tags = tags;
        this.priority = priority;
    }

    public String id() { return id; }
    public String customerName() { return customerName; }
    public List<Item> items() { return items; }
    public BigDecimal total() { return total; }
    public OrderStatus status() { return status; }
    public String couponCode() { return couponCode == null ? "" : couponCode; }
    public String shippingAddress() { return shippingAddress == null ? "" : shippingAddress; }
    public String notes() { return notes == null ? "" : notes; }
    public List<String> tags() { return tags == null ? List.of() : tags; }
    public OrderPriority priority() { return priority == null ? OrderPriority.NORMAL : priority; }

    @Override
    public String toString() {
        return "TelescopingOrder{id=%s, customer=%s, items=%d, total=%s, status=%s}"
                .formatted(id, customerName, items.size(), total, status);
    }
}
