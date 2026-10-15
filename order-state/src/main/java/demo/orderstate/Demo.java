package demo.orderstate;

import demo.orderstate.legacy.LegacyOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 状态模式：GoF 原版 vs Java 25 版。
 *
 * <p>两套实现处理完全相同的业务规则，这个 Demo 把它们并排放着跑，
 * 让你看见"同样的需求，两种写法的代码量差多少"。
 */
public final class Demo {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        heading("合法路径：下单 → 付款 → 发货 → 确认收货");
        modernHappyPath();
        legacyHappyPath();

        heading("非法流转：待付款直接发货");
        modernIllegal();
        legacyIllegal();

        heading("guard：付款 8 天后申请退款");
        modernRefundTooLate();
        legacyRefundTooLate();

        heading("终态不可再转");
        terminalState();

        heading("代码量对比");
        codeSize();

        out();
    }

    // ---------- Java 25 版 ----------

    private static void modernHappyPath() {
        var order = Order.placed("SO-20260301-001", Money.cny("128.00"), T0);
        trace("下单        ", order);

        order = order.transitionTo(new OrderState.Paid("PAY-7788", T0.plus(30, ChronoUnit.MINUTES)), T0.plus(30, ChronoUnit.MINUTES));
        trace("付款        ", order);

        order = order.transitionTo(new OrderState.Shipped("顺丰", "SF1234567"), T0.plus(1, ChronoUnit.DAYS));
        trace("发货        ", order);

        order = order.transitionTo(new OrderState.Completed(T0.plus(4, ChronoUnit.DAYS)), T0.plus(4, ChronoUnit.DAYS));
        trace("确认收货     ", order);
    }

    private static void modernIllegal() {
        var order = Order.placed("SO-20260301-002", Money.cny("39.90"), T0);
        try {
            order.transitionTo(new OrderState.Shipped("顺丰", "SF7654321"), T0);
        } catch (IllegalStateException e) {
            out("  ✗ %s".formatted(e.getMessage()));
            out("    ↑ 注意：这条非法路径根本走不到代码里，是 canTransitionTo 里的");
            out("      switch 穷尽表挡下来的。");
        }
    }

    private static void modernRefundTooLate() {
        var paidAt = T0.plus(1, ChronoUnit.DAYS);
        var order = Order.placed("SO-20260301-003", Money.cny("268.00"), T0)
                .transitionTo(new OrderState.Paid("PAY-9911", paidAt), paidAt);

        var tooLate = paidAt.plus(8, ChronoUnit.DAYS);
        try {
            order.transitionTo(new OrderState.Refunded(Money.cny("268.00"), tooLate), tooLate);
        } catch (IllegalStateException e) {
            out("  ✗ %s".formatted(e.getMessage()));
        }

        var justInTime = paidAt.plus(6, ChronoUnit.DAYS);
        var ok = order.transitionTo(new OrderState.Refunded(Money.cny("268.00"), justInTime), justInTime);
        out("  ✓ 第 6 天退: %s".formatted(ok.describe()));
    }

    private static void terminalState() {
        var cancelled = Order.placed("SO-20260301-004", Money.cny("15.00"), T0)
                .transitionTo(new OrderState.Cancelled("用户改主意了"), T0.plus(10, ChronoUnit.MINUTES));
        out("  %s —— 终态".formatted(cancelled.describe()));
        try {
            cancelled.transitionTo(new OrderState.Paid("PAY-0000", T0), T0);
        } catch (IllegalStateException e) {
            out("  ✗ %s".formatted(e.getMessage()));
        }
    }

    // ---------- GoF 原版 ----------

    private static void legacyHappyPath() {
        var order = LegacyOrder.placed("SO-20260301-101", Money.cny("128.00"), T0);
        trace("下单        ", order);

        order.pay("PAY-7788", T0.plus(30, ChronoUnit.MINUTES));
        trace("付款        ", order);

        order.ship("顺丰", "SF1234567", T0.plus(1, ChronoUnit.DAYS));
        trace("发货        ", order);

        order.complete(T0.plus(4, ChronoUnit.DAYS));
        trace("确认收货     ", order);
    }

    private static void legacyIllegal() {
        var order = LegacyOrder.placed("SO-20260301-102", Money.cny("39.90"), T0);
        try {
            order.ship("顺丰", "SF7654321", T0);
        } catch (IllegalStateException e) {
            out("  ✗ %s".formatted(e.getMessage()));
            out("    ↑ 注意：这条非法路径是被 CreatedState.ship() 里那句");
            out("      throw reject(o, \"发货\") 挡下来的。");
        }
    }

    private static void legacyRefundTooLate() {
        var paidAt = T0.plus(1, ChronoUnit.DAYS);
        var order = LegacyOrder.placed("SO-20260301-103", Money.cny("268.00"), T0)
                .pay("PAY-9911", paidAt);

        try {
            order.refund(Money.cny("268.00"), paidAt.plus(8, ChronoUnit.DAYS));
        } catch (IllegalStateException e) {
            out("  ✗ %s".formatted(e.getMessage()));
        }

        order.refund(Money.cny("268.00"), paidAt.plus(6, ChronoUnit.DAYS));
        out("  ✓ 第 6 天退: %s".formatted(order.describe()));
    }

    // ---------- 对比 ----------

    private static void codeSize() {
        out("""
                          GoF 原版            Java 25 版
                状态定义      6 个类 + 1 接口        1 个 sealed interface + 6 个 record
                接口方法      6 个                 0 个（状态自己带数据）
                实现方法      36 个                1 个 default 方法
                真正有逻辑的    6 个                 1 个 switch 表
                抛异常的方法   30 个                 0 个
                订单对象      可变，状态被换掉       不可变，转移返回新对象
                新增状态时    要写 6 个类            sealed 的 switch 编译不过，提醒你补全""");
    }

    // ---------- 输出辅助 ----------

    private static void trace(String action, Order order) {
        out("  %s %s".formatted(action, order.describe()));
    }

    private static void trace(String action, LegacyOrder order) {
        out("  %s %s".formatted(action, order.describe()));
    }

    private static void heading(String title) {
        out();
        out("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}