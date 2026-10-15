package demo.orderstate.legacy;

import demo.orderstate.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * GoF 原版状态模式 —— 和 {@code demo.orderstate.OrderState} 对照着看。
 *
 * <p><b>刻意把 6 个状态类放进一个文件</b>（都是包私有类）。真实项目应该一类一文件，
 * 这里只是为了让你在一屏之内看完整个模式长什么样。
 *
 * <p>GoF 原版的形状：
 * <ul>
 *   <li>接口有 6 个方法（每个可能的下一步一个）</li>
 *   <li>每个状态类实现全部 6 个方法，5 个抛异常 1 个干活</li>
 *   <li>状态类<b>持有订单引用</b>，干完活顺手把订单的状态换掉</li>
 * </ul>
 *
 * 6 个状态 × 6 个方法 = 36 个方法，其中 30 个只会抛 UnsupportedOperationException。
 */
final class LegacyStates {

    private LegacyStates() {
    }

    /** 状态接口。每个可能的转移一个方法。 */
    public interface LegacyState {
        void pay(LegacyOrder o, String paymentId, Instant at);

        void ship(LegacyOrder o, String carrier, String trackingNo, Instant at);

        void complete(LegacyOrder o, Instant at);

        void cancel(LegacyOrder o, String reason, Instant at);

        void refund(LegacyOrder o, Money amount, Instant at);
    }

    static final class CreatedState implements LegacyState {
        @Override
        public void pay(LegacyOrder o, String paymentId, Instant at) {
            o.moveTo(new PaidState(), new PaidInfo(paymentId, at), at);
        }

        @Override
        public void ship(LegacyOrder o, String carrier, String trackingNo, Instant at) {
            throw reject(o, "发货");
        }

        @Override
        public void complete(LegacyOrder o, Instant at) {
            throw reject(o, "完成");
        }

        @Override
        public void cancel(LegacyOrder o, String reason, Instant at) {
            o.moveTo(new CancelledState(), reason, at);
        }

        @Override
        public void refund(LegacyOrder o, Money amount, Instant at) {
            throw reject(o, "退款");
        }
    }

    static final class PaidState implements LegacyState {
        @Override
        public void pay(LegacyOrder o, String paymentId, Instant at) {
            throw reject(o, "付款");
        }

        @Override
        public void ship(LegacyOrder o, String carrier, String trackingNo, Instant at) {
            o.moveTo(new ShippedState(), new Tracking(carrier, trackingNo), at);
        }

        @Override
        public void complete(LegacyOrder o, Instant at) {
            throw reject(o, "完成");
        }

        @Override
        public void cancel(LegacyOrder o, String reason, Instant at) {
            o.moveTo(new CancelledState(), reason, at);
        }

        // 已付款可以退款。金额校验在 LegacyOrder.refund 里（guard 和状态转移是两件事）
        @Override
        public void refund(LegacyOrder o, Money amount, Instant at) {
            o.moveTo(new RefundedState(), amount, at);
        }
    }

    static final class ShippedState implements LegacyState {
        @Override
        public void pay(LegacyOrder o, String paymentId, Instant at) {
            throw reject(o, "付款");
        }

        @Override
        public void ship(LegacyOrder o, String carrier, String trackingNo, Instant at) {
            throw reject(o, "发货");
        }

        @Override
        public void complete(LegacyOrder o, Instant at) {
            o.moveTo(new CompletedState(), at, at);
        }

        @Override
        public void cancel(LegacyOrder o, String reason, Instant at) {
            throw reject(o, "取消");
        }

        @Override
        public void refund(LegacyOrder o, Money amount, Instant at) {
            o.moveTo(new RefundedState(), amount, at);
        }
    }

    static final class CompletedState implements LegacyState {
        @Override
        public void pay(LegacyOrder o, String paymentId, Instant at) {
            throw reject(o, "付款");
        }

        @Override
        public void ship(LegacyOrder o, String carrier, String trackingNo, Instant at) {
            throw reject(o, "发货");
        }

        @Override
        public void complete(LegacyOrder o, Instant at) {
            throw reject(o, "完成");
        }

        @Override
        public void cancel(LegacyOrder o, String reason, Instant at) {
            throw reject(o, "取消");
        }

        @Override
        public void refund(LegacyOrder o, Money amount, Instant at) {
            throw reject(o, "退款");
        }
    }

    static final class CancelledState implements LegacyState {
        @Override
        public void pay(LegacyOrder o, String paymentId, Instant at) {
            throw reject(o, "付款");
        }

        @Override
        public void ship(LegacyOrder o, String carrier, String trackingNo, Instant at) {
            throw reject(o, "发货");
        }

        @Override
        public void complete(LegacyOrder o, Instant at) {
            throw reject(o, "完成");
        }

        @Override
        public void cancel(LegacyOrder o, String reason, Instant at) {
            throw reject(o, "取消");
        }

        @Override
        public void refund(LegacyOrder o, Money amount, Instant at) {
            throw reject(o, "退款");
        }
    }

    static final class RefundedState implements LegacyState {
        @Override
        public void pay(LegacyOrder o, String paymentId, Instant at) {
            throw reject(o, "付款");
        }

        @Override
        public void ship(LegacyOrder o, String carrier, String trackingNo, Instant at) {
            throw reject(o, "发货");
        }

        @Override
        public void complete(LegacyOrder o, Instant at) {
            throw reject(o, "完成");
        }

        @Override
        public void cancel(LegacyOrder o, String reason, Instant at) {
            throw reject(o, "取消");
        }

        @Override
        public void refund(LegacyOrder o, Money amount, Instant at) {
            throw reject(o, "退款");
        }
    }

    // ---- 状态携带的数据 ----

    record PaidInfo(String paymentId, Instant paidAt) {
        boolean withinRefundWindow(Instant now) {
            return !paidAt.plus(Duration.ofDays(7)).isBefore(now);
        }
    }

    record Tracking(String carrier, String trackingNo) {
    }

    static IllegalStateException reject(LegacyOrder o, String action) {
        return new IllegalStateException(
                "订单 %s 当前是「%s」，不能%s".formatted(o.id(), o.stateLabel(), action));
    }
}