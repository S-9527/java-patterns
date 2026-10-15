package demo.builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 订单 —— <b>不可变 record</b>，通过 Builder 创建。
 *
 * <p>5 个必填 + 5 个可选字段。不用建造者的话：
 * <ul>
 *   <li>伸缩构造器：10 个参数，6 个 {@code null}</li>
 *   <li>可变 JavaBean：可以改，测试时容易出残留状态</li>
 * </ul>
 */
public record Order(
        // 必填
        String id,
        String customerName,
        List<Item> items,
        BigDecimal total,
        OrderStatus status,
        // 可选
        String couponCode,
        String shippingAddress,
        String notes,
        List<String> tags,
        OrderPriority priority) {

    public Order {
        Objects.requireNonNull(id, "订单 ID 不能为空");
        Objects.requireNonNull(customerName, "客户名不能为空");
        Objects.requireNonNull(items, "商品列表不能为空");
        Objects.requireNonNull(total, "总价不能为空");
        Objects.requireNonNull(status, "状态不能为空");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("订单至少需要一个商品");
        }
    }

    /** 可选字段的默认值。 */
    public String couponCode() {
        return couponCode == null ? "" : couponCode;
    }

    public String shippingAddress() {
        return shippingAddress == null ? "" : shippingAddress;
    }

    public String notes() {
        return notes == null ? "" : notes;
    }

    public List<String> tags() {
        return tags == null ? List.of() : tags;
    }

    public OrderPriority priority() {
        return priority == null ? OrderPriority.NORMAL : priority;
    }

    @Override
    public String toString() {
        return ("Order{id=%s, customer=%s, items=%d, total=%s, status=%s, " +
                "coupon='%s', priority=%s, tags=%s}")
                .formatted(id, customerName, items.size(), total, status,
                        couponCode(), priority(), tags());
    }

    /** 渐进式 Builder —— 同一个 builder 实例可以被多次 build()。 */
    public static class Builder {
        private String id;
        private String customerName;
        private List<Item> items;
        private BigDecimal total;
        private OrderStatus status;
        private String couponCode;
        private String shippingAddress;
        private String notes;
        private List<String> tags;
        private OrderPriority priority;

        public Builder() {
            this.status = OrderStatus.CREATED;
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder customerName(String name) { this.customerName = name; return this; }
        public Builder items(List<Item> items) { this.items = items; return this; }
        public Builder total(BigDecimal total) { this.total = total; return this; }
        public Builder status(OrderStatus status) { this.status = status; return this; }
        public Builder couponCode(String code) { this.couponCode = code; return this; }
        public Builder shippingAddress(String addr) { this.shippingAddress = addr; return this; }
        public Builder notes(String notes) { this.notes = notes; return this; }
        public Builder tags(List<String> tags) { this.tags = tags; return this; }
        public Builder priority(OrderPriority p) { this.priority = p; return this; }

        /**
         * 构建订单。必填校验集中在这里，不在 setter 里。
         *
         * <p>渐进式 builder 的关键：<b>build() 不消耗 builder 实例</b>，
         * 可以多次调用 build() 得到不同变体。
         */
        public Order build() {
            var missing = new java.util.ArrayList<String>();
            if (id == null) missing.add("id");
            if (customerName == null) missing.add("customerName");
            if (items == null) missing.add("items");
            if (total == null) missing.add("total");
            if (status == null) missing.add("status");

            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "订单缺少必填字段：%s".formatted(missing));
            }

            return new Order(id, customerName, items, total, status,
                    couponCode, shippingAddress, notes, tags, priority);
        }
    }

    /** 从头开始一个新的 Builder。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 从当前订单派生一个新 Builder（局部修改用）。 */
    public Builder toBuilder() {
        return new Builder()
                .id(id).customerName(customerName).items(items)
                .total(total).status(status)
                .couponCode(couponCode).shippingAddress(shippingAddress)
                .notes(notes).tags(tags).priority(priority);
    }
}
