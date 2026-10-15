package demo.orderstate;

import demo.orderstate.legacy.LegacyOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 等价性测试：GoF 原版和 Java 25 版处理<b>完全相同的业务规则</b>，
 * 这个测试跑同一条路径在两套实现上，断言行为一致。
 *
 * <p>这是本项目里最有说服力的一类测试 —— 它证明 sealed + switch 不是"另一种写法"，
 * 而是能精确复现状态模式语义的东西。
 */
@DisplayName("GoF 原版 vs Java 25 版 —— 行为等价")
class EquivalenceTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Money TOTAL = Money.cny("128.00");

    private static final Instant PAID_AT = T0.plus(30, ChronoUnit.MINUTES);
    private static final Instant SHIPPED_AT = T0.plus(1, ChronoUnit.DAYS);
    private static final Instant DONE_AT = T0.plus(4, ChronoUnit.DAYS);

    @Test
    @DisplayName("合法路径：两套实现到达同一终态")
    void sameHappyPath() {
        var modern = Order.placed("SO-1", TOTAL, T0)
                .transitionTo(new OrderState.Paid("PAY-1", PAID_AT), PAID_AT)
                .transitionTo(new OrderState.Shipped("顺丰", "SF1"), SHIPPED_AT)
                .transitionTo(new OrderState.Completed(DONE_AT), DONE_AT);

        var legacy = LegacyOrder.placed("SO-1", TOTAL, T0)
                .pay("PAY-1", PAID_AT)
                .ship("顺丰", "SF1", SHIPPED_AT)
                .complete(DONE_AT);

        assertEquals(legacy.describe(), modern.describe());
        assertEquals("SO-1 [已完成] CNY 128.00", modern.describe());
    }

    @Test
    @DisplayName("非法流转：两套实现都拒绝，且给出同样可读的原因")
    void bothRejectIllegalTransition() {
        var modernEx = assertThrows(IllegalStateException.class, () ->
                Order.placed("SO-1", TOTAL, T0)
                        .transitionTo(new OrderState.Shipped("顺丰", "SF1"), T0));
        var legacyEx = assertThrows(IllegalStateException.class, () ->
                LegacyOrder.placed("SO-1", TOTAL, T0).ship("顺丰", "SF1", T0));

        // 两边措辞略有不同（这正是可以商量的地方），但都点明了当前状态和被拒动作
        assertEquals("订单 SO-1 不能从「待付款」转到「已发货」", modernEx.getMessage());
        assertEquals("订单 SO-1 当前是「待付款」，不能发货", legacyEx.getMessage());
    }

    @Test
    @DisplayName("退款窗口 guard：两套实现都拦，超过窗口都放行")
    void sameRefundGuard() {
        var modernPaid = Order.placed("SO-1", TOTAL, T0)
                .transitionTo(new OrderState.Paid("PAY-1", T0), T0);
        var legacyPaid = LegacyOrder.placed("SO-1", TOTAL, T0).pay("PAY-1", T0);

        var late = T0.plus(8, ChronoUnit.DAYS);
        assertThrows(IllegalStateException.class,
                () -> modernPaid.transitionTo(new OrderState.Refunded(TOTAL, late), late));
        assertThrows(IllegalStateException.class,
                () -> legacyPaid.refund(TOTAL, late));

        var inTime = T0.plus(6, ChronoUnit.DAYS);
        assertEquals("SO-1 [已退款] CNY 128.00",
                modernPaid.transitionTo(new OrderState.Refunded(TOTAL, inTime), inTime).describe());
        assertEquals("SO-1 [已退款] CNY 128.00",
                legacyPaid.refund(TOTAL, inTime).describe());
    }

    @Test
    @DisplayName("⚠ 唯一的行为差异：可变 vs 不可变")
    void theOneRealDifference() {
        var modern = Order.placed("SO-1", TOTAL, T0);
        var legacy = LegacyOrder.placed("SO-1", TOTAL, T0);

        modern.transitionTo(new OrderState.Paid("PAY-1", T0), T0);
        legacy.pay("PAY-1", T0);

        // GoF 版：调用方手上的引用"跟着变了"，而且看不出是什么时候变的
        assertEquals("SO-1 [已付款] CNY 128.00", legacy.describe(),
                "GoF 版是原地改，调用方不需要接返回值也看到了新状态");

        // Java 25 版：不接返回值就等于什么都没发生，这是刻意的
        assertEquals("SO-1 [待付款] CNY 128.00", modern.describe(),
                "Java 25 版是不可变的 —— 想改变量必须接返回值并重新赋值");
    }
}