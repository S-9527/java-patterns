package demo.orderstate;

import java.time.Instant;

/**
 * 订单聚合 —— Java 25 式：不可变 record，状态是它的一个组件。
 *
 * <p>{@code transitionTo} 返回<b>新订单</b>而不是原地改。这和 GoF 原版的可变对象是对照的：
 * 原版里 {@code order.pay()} 会把 order 内部的状态指针换掉，调用方拿到的还是同一个引用，
 * 一旦有并发就很难讲清楚。这里没有这个问题 —— 状态转移是纯函数。
 */
public record Order(String id, OrderState state, Money total, Instant updatedAt) {

    public Order {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("订单状态不能为 null");
        }
    }

    public static Order placed(String id, Money total, Instant at) {
        return new Order(id, new OrderState.Created(at), total, at);
    }

    /**
     * 转到目标状态，返回新订单。
     *
     * @param now 当前时间，用于判断退款窗口这类带时间的 guard
     * @throws IllegalStateException 非法流转，或违反 guard
     */
    public Order transitionTo(OrderState target, Instant now) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单 %s 不能从「%s」转到「%s」".formatted(id, state.label(), target.label()));
        }

        // guard 用 switch + when 表达，而不是一串 if
        String violation = switch (state) {
            case OrderState.Paid p
                    when target instanceof OrderState.Refunded && !p.withinRefundWindow(now) ->
                    "订单 %s 付款已超过 %d 天，无法退款"
                            .formatted(id, REFUND_DAYS);
            default -> null;
        };
        if (violation != null) {
            throw new IllegalStateException(violation);
        }

        return new Order(id, target, total, now);
    }

    private static final int REFUND_DAYS = (int) OrderState.REFUND_WINDOW.toDays();

    public String describe() {
        return "%s [%s] %s".formatted(id, state.label(), total);
    }
}