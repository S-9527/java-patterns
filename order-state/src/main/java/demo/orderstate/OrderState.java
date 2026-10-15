package demo.orderstate;

import java.time.Duration;
import java.time.Instant;

/**
 * 订单状态 —— <b>Java 25 式实现</b>：sealed + 模式匹配 switch，不写 default。
 *
 * <p>这是状态模式的现代写法，和 {@code demo.orderstate.legacy} 包里的 GoF 原版对着看。
 *
 * <p><b>sealed 在这里干了三件事：</b>
 * <ol>
 *   <li>状态集合是封闭且已知的 —— 编译器知道一共有 6 种</li>
 *   <li>{@link #canTransitionTo} 里内层 switch 可以穷尽，不用 default 兜底</li>
 *   <li>往 {@code OrderState} 加一种状态，<b>所有遍历状态的地方都会编译不过</b>，
 *       而不是等到线上某个分支走漏了</li>
 * </ol>
 *
 * <p>每个状态是一个 record，<b>状态自带它需要的数据</b>：Paid 知道付款时间和流水号，
 * Refunded 知道退了多少钱。这比 GoF 原版"每个状态建一个类、类里存一个订单引用"干净得多。
 */
public sealed interface OrderState {

    /** 人类可读标签，异常消息里用。 */
    String label();

    /**
     * 退款窗口。写死 7 天，真实场景该来自配置。
     */
    Duration REFUND_WINDOW = Duration.ofDays(7);

    /**
     * 当前状态能否转到 {@code target}。
     *
     * <p>两层 switch 都不写 default —— 两层都靠 sealed 穷尽。
     * 外层 switch 少一个 case，{@code canTransitionTo} 就编译不过；
     * 内层 switch 少一个 case，对应的 {@code if} 分支就编译不过。
     *
     * <p><b>注意多标签用的是无名模式 {@code _}，不是 {@code case Paid p, Shipped s ->}。</b>
     * JDK 25.0.4 上具名多标签模式会被判 "illegal fall-through from a pattern" 直接拒��编译，
     * 无名模式才通过。这个坑记在 {@code docs/order-state.md} 里。
     *
     * <p>每个内层 switch 都必须包含"转到自身"这一支（比如 Paid → Paid 返回 false），
     * 否则穷尽性检查不通过 —— 漏掉自转移是写这张表时最常见的错。
     */
    default boolean canTransitionTo(OrderState target) {
        return switch (this) {
            // 刚下单：可以去付款，也可以直接取消
            case Created c -> switch (target) {
                case Paid p -> true;
                case Cancelled x -> true;
                case Created _, Shipped _, Completed _, Refunded _ -> false;
            };
            // 已付款：可以发货、取消、退款
            case Paid p -> switch (target) {
                case Shipped s -> true;
                case Refunded r -> true;
                case Cancelled x -> true;
                case Created _, Completed _, Paid _ -> false;
            };
            // 已发货：可以确认收货、申请退款，不能再取消
            case Shipped s -> switch (target) {
                case Completed d -> true;
                case Refunded r -> true;
                case Created _, Paid _, Cancelled _, Shipped _ -> false;
            };
            // 终态
            case Completed _, Cancelled _, Refunded _ -> false;
        };
    }

    // ---------- 6 种状态，全部是 record ----------

    record Created(Instant placedAt) implements OrderState {
        @Override
        public String label() {
            return "待付款";
        }
    }

    record Paid(String paymentId, Instant paidAt) implements OrderState {
        @Override
        public String label() {
            return "已付款";
        }

        /** 付款后 7 天内可退。 */
        boolean withinRefundWindow(Instant now) {
            return !paidAt.plus(REFUND_WINDOW).isBefore(now);
        }
    }

    record Shipped(String carrier, String trackingNo) implements OrderState {
        @Override
        public String label() {
            return "已发货";
        }
    }

    record Completed(Instant completedAt) implements OrderState {
        @Override
        public String label() {
            return "已完成";
        }
    }

    record Cancelled(String reason) implements OrderState {
        @Override
        public String label() {
            return "已取消";
        }
    }

    record Refunded(Money amount, Instant refundedAt) implements OrderState {
        @Override
        public String label() {
            return "已退款";
        }
    }
}