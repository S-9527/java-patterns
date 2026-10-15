package demo.orderstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("订单状态机 —— Java 25 式（sealed + 模式匹配 switch）")
class OrderStateTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Money TOTAL = Money.cny("128.00");

    private static Order fresh() {
        return Order.placed("SO-1", TOTAL, T0);
    }

    @Nested
    @DisplayName("合法流转")
    class Happy {

        @Test
        @DisplayName("待付款 → 已付款 → 已发货 → 已完成")
        void fullPath() {
            var o = fresh();
            o = o.transitionTo(new OrderState.Paid("PAY-1", T0), T0);
            assertInstanceOf(OrderState.Paid.class, o.state());

            o = o.transitionTo(new OrderState.Shipped("顺丰", "SF1"), T0);
            assertInstanceOf(OrderState.Shipped.class, o.state());

            o = o.transitionTo(new OrderState.Completed(T0), T0);
            assertInstanceOf(OrderState.Completed.class, o.state());
        }

        @Test
        @DisplayName("不可变：转移返回新对象，原对象不变")
        void transitionReturnsNewObject() {
            var created = fresh();
            var paid = created.transitionTo(new OrderState.Paid("PAY-1", T0), T0);

            assertInstanceOf(OrderState.Created.class, created.state(), "原对象状态没被动过");
            assertInstanceOf(OrderState.Paid.class, paid.state());
            assertEquals("SO-1", paid.id());
            assertEquals(TOTAL, paid.total(), "业务数据原样带过去");
        }

        @Test
        @DisplayName("待付款可以直接取消")
        void cancelFromCreated() {
            var cancelled = fresh().transitionTo(new OrderState.Cancelled("用户改主意"), T0);
            assertInstanceOf(OrderState.Cancelled.class, cancelled.state());
        }
    }

    @Nested
    @DisplayName("非法流转 —— 被穷尽表挡下")
    class Illegal {

        @Test
        @DisplayName("待付款不能直接发货")
        void cannotShipBeforePaying() {
            var e = assertThrows(IllegalStateException.class,
                    () -> fresh().transitionTo(new OrderState.Shipped("顺丰", "SF1"), T0));
            assertEquals("订单 SO-1 不能从「待付款」转到「已发货」", e.getMessage());
        }

        @Test
        @DisplayName("待付款不能直接完成")
        void cannotCompleteBeforeShipping() {
            assertThrows(IllegalStateException.class,
                    () -> fresh().transitionTo(new OrderState.Completed(T0), T0));
        }

        @Test
        @DisplayName("已发货不能取消")
        void cannotCancelAfterShipped() {
            var shipped = fresh()
                    .transitionTo(new OrderState.Paid("PAY-1", T0), T0)
                    .transitionTo(new OrderState.Shipped("顺丰", "SF1"), T0);
            assertThrows(IllegalStateException.class,
                    () -> shipped.transitionTo(new OrderState.Cancelled("不想要了"), T0));
        }

        @Test
        @DisplayName("终态不能再转")
        void terminalStates() {
            var completed = fresh()
                    .transitionTo(new OrderState.Paid("PAY-1", T0), T0)
                    .transitionTo(new OrderState.Shipped("顺丰", "SF1"), T0)
                    .transitionTo(new OrderState.Completed(T0), T0);
            assertFalse(completed.state().canTransitionTo(new OrderState.Paid("X", T0)));

            var cancelled = fresh().transitionTo(new OrderState.Cancelled("算了"), T0);
            assertFalse(cancelled.state().canTransitionTo(new OrderState.Paid("X", T0)));
        }
    }

    @Nested
    @DisplayName("guard：退款窗口")
    class Guards {

        private Order paidAt(Instant paidAt) {
            return fresh().transitionTo(new OrderState.Paid("PAY-1", paidAt), paidAt);
        }

        @Test
        @DisplayName("付款后 7 天内可退")
        void refundWithinWindow() {
            var paidAt = T0.plus(1, ChronoUnit.DAYS);
            var order = paidAt(paidAt);
            var refunded = order.transitionTo(
                    new OrderState.Refunded(TOTAL, paidAt.plus(6, ChronoUnit.DAYS)),
                    paidAt.plus(6, ChronoUnit.DAYS));
            assertInstanceOf(OrderState.Refunded.class, refunded.state());
        }

        @Test
        @DisplayName("超过 7 天：guard 挡下，即使流转表允许 Paid → Refunded")
        void refundAfterWindowBlockedByGuard() {
            var paidAt = T0.plus(1, ChronoUnit.DAYS);
            var order = paidAt(paidAt);

            // 流转表说可以
            assertTrue(order.state().canTransitionTo(new OrderState.Refunded(TOTAL, T0)));

            // 但 guard 拦下来了 —— 这就是模式匹配 switch 里 when 的作用
            var e = assertThrows(IllegalStateException.class, () -> order.transitionTo(
                    new OrderState.Refunded(TOTAL, paidAt.plus(8, ChronoUnit.DAYS)),
                    paidAt.plus(8, ChronoUnit.DAYS)));
            assertTrue(e.getMessage().contains("超过 7 天"));
        }

        @Test
        @DisplayName("第 7 天当天仍在窗口内")
        void boundaryDay() {
            var paidAt = T0.plus(1, ChronoUnit.DAYS);
            var order = paidAt(paidAt);
            var day7 = paidAt.plus(7, ChronoUnit.DAYS);
            assertInstanceOf(OrderState.Refunded.class,
                    order.transitionTo(new OrderState.Refunded(TOTAL, day7), day7).state());
        }

        @Test
        @DisplayName("guard 只作用于 Paid → Refunded，不影响已发货后退款")
        void guardOnlyAppliesToPaidState() {
            var paidAt = T0;
            var shipped = fresh()
                    .transitionTo(new OrderState.Paid("PAY-1", paidAt), paidAt)
                    .transitionTo(new OrderState.Shipped("顺丰", "SF1"), paidAt);

            // 已发货很久以后退款不受 7 天窗口限制
            var muchLater = T0.plus(90, ChronoUnit.DAYS);
            assertInstanceOf(OrderState.Refunded.class,
                    shipped.transitionTo(new OrderState.Refunded(TOTAL, muchLater), muchLater).state());
        }
    }

    @Test
    @DisplayName("状态自带数据 —— 这是 record 相对 GoF 状态类的最大优势")
    void stateCarriesItsOwnData() {
        var paid = new OrderState.Paid("PAY-20260301-7788", T0);
        assertEquals("PAY-20260301-7788", paid.paymentId());
        assertEquals(T0, paid.paidAt());

        var shipped = new OrderState.Shipped("顺丰速运", "SF1234567890");
        assertEquals("顺丰速运", shipped.carrier());
        assertEquals("SF1234567890", shipped.trackingNo());
    }

    @Test
    @DisplayName("状态集合是封闭的 —— 这正是编译期能查漏的前提")
    void statesAreSealed() {
        var permitted = OrderState.class.getPermittedSubclasses();
        assertEquals(6, permitted.length, "sealed 保证状态集合封闭且已知");
    }
}