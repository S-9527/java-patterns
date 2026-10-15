package demo.command;

import java.math.BigDecimal;
import java.util.List;

/** 订单的当前状态。备忘录要快照的就是它。 */
public record OrderState(String orderId, OrderStatus status, BigDecimal total,
                         List<String> history) {

    public OrderState {
        history = List.copyOf(history);
    }

    /** 快照用深拷贝语义：history 是不可变的 record，所以引用共享是安全的。 */
    public OrderState withStatus(OrderStatus next) {
        var newHistory = new java.util.ArrayList<>(history);
        // 用 label() 不是 toString() —— toString 给的是 CREATED 这种枚举名，
        // 审计日志里没人看得懂"CREATED → PAID"，要的是"待付款 → 已付款"
        newHistory.add("%s → %s".formatted(status.label(), next.label()));
        return new OrderState(orderId, next, total, newHistory);
    }

    public enum OrderStatus {
        CREATED("待付款"),
        PAID("已付款"),
        SHIPPED("已发货"),
        CANCELLED("已取消");

        private final String label;

        OrderStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}