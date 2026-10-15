package demo.builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * 可变 JavaBean —— <b>不用建造者</b>的写法 2。
 *
 * <p>Setter 模式，每个字段都能改。代价：
 * <ul>
 *   <li>没有不可变性保证 —— 对象可以在任何时刻被改</li>
 *   <li>没有必填校验 —— setter 里不校验，到用的时候才发现缺字段</li>
 *   <li>测试时容易出残留状态 —— 改了一个测试会影响下一个</li>
 * </ul>
 */
public final class MutableOrder {

    private String id;
    private String customerName;
    private List<Item> items;
    private BigDecimal total;
    private OrderStatus status = OrderStatus.CREATED;
    private String couponCode;
    private String shippingAddress;
    private String notes;
    private List<String> tags;
    private OrderPriority priority = OrderPriority.NORMAL;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String name) { this.customerName = name; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String code) { this.couponCode = code; }

    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String addr) { this.shippingAddress = addr; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public OrderPriority getPriority() { return priority; }
    public void setPriority(OrderPriority p) { this.priority = p; }

    /** ⚠ 没有校验 —— 调用方要自己保证必填字段已设置。 */
    public Order toOrder() {
        return new Order(id, customerName, items, total, status,
                couponCode, shippingAddress, notes, tags, priority);
    }

    @Override
    public String toString() {
        return "MutableOrder{id=%s, customer=%s, status=%s}"
                .formatted(id, customerName, status);
    }
}
