package demo.orderstate.legacy;

import demo.orderstate.Money;

import java.time.Instant;

import static demo.orderstate.legacy.LegacyStates.CancelledState;
import static demo.orderstate.legacy.LegacyStates.CompletedState;
import static demo.orderstate.legacy.LegacyStates.CreatedState;
import static demo.orderstate.legacy.LegacyStates.LegacyState;
import static demo.orderstate.legacy.LegacyStates.PaidInfo;
import static demo.orderstate.legacy.LegacyStates.PaidState;
import static demo.orderstate.legacy.LegacyStates.RefundedState;
import static demo.orderstate.legacy.LegacyStates.ShippedState;

/**
 * GoF 原版：<b>可变</b>聚合，状态在内部被换掉。
 *
 * <p>调用方拿到的永远是同一个引用。{@code order.pay()} 执行完之后，
 * 之前持有这个 order 的地方看到的也是新状态 —— 这在单线程下很方便，
 * 但一旦有人把 order 传给另一个线程，"我手上的 order 还是待付款吗"就成了竞态问题。
 *
 * <p>Java 25 版的 {@code demo.orderstate.Order} 是不可变的，{@code transitionTo} 返回新对象，
 * 不存在这个问题。
 */
public final class LegacyOrder {

    private final String id;
    private final Money total;

    private LegacyState state;
    private Object stateData;
    private Instant updatedAt;

    LegacyOrder(String id, Money total, Instant placedAt) {
        this.id = id;
        this.total = total;
        this.state = new CreatedState();
        this.stateData = placedAt;
        this.updatedAt = placedAt;
    }

    // 状态类在干活的过程中回调这三个方法改订单内部

    void moveTo(LegacyState next, Object data, Instant at) {
        this.state = next;
        this.stateData = data;
        this.updatedAt = at;
    }

    String stateLabel() {
        return switch (state) {
            case CreatedState s -> "待付款";
            case PaidState s -> "已付款";
            case ShippedState s -> "已发货";
            case CompletedState s -> "已完成";
            case CancelledState s -> "已取消";
            case RefundedState s -> "已退款";
            default -> state.getClass().getSimpleName();
        };
    }

    public String id() {
        return id;
    }

    public Money total() {
        return total;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    // ---------- 对外的转移入口：一行转发给当前状态 ----------

    public LegacyOrder pay(String paymentId, Instant at) {
        state.pay(this, paymentId, at);
        return this;
    }

    public LegacyOrder ship(String carrier, String trackingNo, Instant at) {
        state.ship(this, carrier, trackingNo, at);
        return this;
    }

    public LegacyOrder complete(Instant at) {
        state.complete(this, at);
        return this;
    }

    public LegacyOrder cancel(String reason, Instant at) {
        state.cancel(this, reason, at);
        return this;
    }

    public LegacyOrder refund(Money amount, Instant at) {
        // guard 不在状态类里，而是在聚合上 —— 因为它需要跨状态读 PaidInfo
        if (state instanceof PaidState && stateData instanceof PaidInfo p
                && !p.withinRefundWindow(at)) {
            throw new IllegalStateException("订单 %s 付款已超过 7 天，无法退款".formatted(id));
        }
        state.refund(this, amount, at);
        return this;
    }

    public String describe() {
        return "%s [%s] %s".formatted(id, stateLabel(), total);
    }

    /** 给测试和 Demo 用的工厂。 */
    public static LegacyOrder placed(String id, Money total, Instant at) {
        return new LegacyOrder(id, total, at);
    }

    Object stateData() {
        return stateData;
    }
}