package demo.events;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

/**
 * 观察者：订单事件的订阅与分发。
 *
 * <p>三件事：
 * <ol>
 *   <li>加几个监听器，其中一个会抛异常 —— 看它是否影响别人</li>
 *   <li>失败隔离 vs 失败传播，两种策略对比</li>
 *   <li>退订，以及 sealed switch 在日志汇总里的穷尽性</li>
 * </ol>
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var cny = Currency.getInstance("CNY");
        var placed = new OrderEvent.OrderPlaced("SO-1001", "U-2001", 3, "2026-03-01T10:00:00Z");
        var paid = new OrderEvent.PaymentCaptured("SO-1001", new BigDecimal("268.00"), cny,
                "ALIPAY", "2026-03-01T10:00:30Z");
        var cancelled = new OrderEvent.OrderCancelled("SO-1001", "用户改主意了",
                "2026-03-01T11:00:00Z");

        heading("一个订单引发四个下游动作");
        var bus = buildFullBus();
        System.out.println("  注册了 %d 个监听器%n".formatted(bus.listenerCount()));

        for (var event : List.of(placed, paid, cancelled)) {
            var results = bus.publish(event);
            System.out.println("  发布 %-22s → %s".formatted(typeName(event), DispatchResult.summarize(results)));
            System.out.print(DispatchResult.dispatchAdvice(results));
            System.out.println();
        }

        heading("SMS 监听器抛异常了 —— 其他监听器照跑不误");
        System.out.println("  上面的 ✗ 就是：短信网关超时，被隔离成 Failed。");
        System.out.println("  积分、日志、风控全都正常收到了事件 —— 这是隔离的价值。");
        System.out.println("  如果不隔离，用户支付成功会因为短信失败而回滚，不可接受。");
        System.out.println();
        System.out.println("  ⚠ 但注意它的处置建议是「重试无用，应告警」，这不对：");
        System.out.println("    短信网关超时显然该重试。原因是这里抛的是 RuntimeException，");
        System.out.println("    而 isRetryable 只认 SocketTimeoutException / IOException。");
        System.out.println("    用真正的受检异常就判对了 —— 见 OrderEventBusTest.retryabilityClassification。");

        heading("另一个极端：失败传播");
        var strict = buildFullBus().propagateFailures(true);
        try {
            strict.publish(paid);
        } catch (OrderEventBus.ListenerFailedException e) {
            System.out.println("  ✗ %s".formatted(e.getMessage()));
            System.out.println("    支付成功的订单被当成失败了 —— 这就是为什么默认不这么干。");
        }

        heading("可重试 vs 不可重试");
        demoRetryAdvice();

        heading("退订");
        var unsubscribing = buildHealthyBus();
        System.out.println("  退订前 %d 个监听器".formatted(unsubscribing.listenerCount()));
        unsubscribing.on("加积分", e -> {
        }).listenerCount();      // 再加一个，演示差异
        System.out.println("  加了一个之后 %d 个".formatted(unsubscribing.listenerCount()));

        var afterUnsub = buildHealthyBus();
        System.out.println();
        System.out.println("  观察者模式的一个已知弱点：退订要走 unregister，");
        System.out.println("  而发布方并不认识具体监听器，调用方得自己拿着 bus 引用去退。");
        System.out.println("  EventBus 记下来的一共发布了 %d 个事件（已发布的不会因为退订而消失）"
                .formatted(afterUnsub.publishedEvents().size()));

        heading("sealed switch 的穷尽性");
        demoSealedExhaustiveness(cny);

        System.out.println();
    }

    private static OrderEventBus buildFullBus() {
        return new OrderEventBus()
                .on("发短信", e -> {
                    // 模拟：只在支付事件上超时。
                    // ⚠ 这里用 RuntimeException 而不是 SocketTimeoutException，
                    // 是因为 isRetryable 只认后者 —— 结果它被判成"重试无用"。
                    // 用受检异常就对了，但 lambda 里写受检异常需要 ThrowingConsumer。
                    if (e instanceof OrderEvent.PaymentCaptured) {
                        throw new RuntimeException("短信网关超时");
                    }
                })
                .on("加积分", e -> {
                })
                .on("写审计日志", e -> {
                })
                .on("通知风控", e -> {
                });
    }

    private static OrderEventBus buildHealthyBus() {
        return new OrderEventBus()
                .on("写审计日志", e -> {
                })
                .on("通知风控", e -> {
                });
    }

    private static void demoRetryAdvice() {
        var retryable = new DispatchResult.Failed("发短信", "connect timed out", true);
        var fatal = new DispatchResult.Failed("发短信", "用户已被拉黑", false);

        System.out.println("  %s → %s".formatted(retryable, retryable.advice()));
        System.out.println("  %s → %s".formatted(fatal, fatal.advice()));
        System.out.println();
        System.out.println("  两者都记 Failed，但处置完全不同。汇总时这句话会分两条列出来：");
        System.out.println("  %s".formatted(DispatchResult.summarize(List.of(retryable, fatal))));
    }

    private static void demoSealedExhaustiveness(Currency cny) {
        var placed = new OrderEvent.OrderPlaced("SO-1", "U-1", 2, "T0");
        var paid = new OrderEvent.PaymentCaptured("SO-1", new BigDecimal("1.00"), cny, "WECHAT", "T1");
        var cancelled = new OrderEvent.OrderCancelled("SO-1", "超时未付", "T2");

        // 汇总所有事件，用 sealed switch 分组 —— 没有 default，加新事件编译不过
        var summary = new StringBuilder();
        var counts = new java.util.EnumMap<EventKind, Integer>(EventKind.class);
        for (var e : List.of(placed, paid, cancelled, paid, placed)) {
            counts.merge(kindOf(e), 1, Integer::sum);
        }
        for (var kind : EventKind.values()) {
            summary.append("  %-12s %d 次%n".formatted(kind.label, counts.getOrDefault(kind, 0)));
        }
        System.out.print(summary);
        System.out.println();
        System.out.println("  kindOf() 里是一个无 default 的 sealed switch ——");
        System.out.println("  往 OrderEvent 加第四种事件时，这里编译不过。");
    }

    private enum EventKind {
        PLACED("已下单"), PAID("已收款"), CANCELLED("已取消");

        final String label;

        EventKind(String label) {
            this.label = label;
        }
    }

    /** 无 default 的 sealed switch。 */
    private static EventKind kindOf(OrderEvent event) {
        return switch (event) {
            case OrderEvent.OrderPlaced _ -> EventKind.PLACED;
            case OrderEvent.PaymentCaptured _ -> EventKind.PAID;
            case OrderEvent.OrderCancelled _ -> EventKind.CANCELLED;
        };
    }

    private static String typeName(OrderEvent event) {
        return switch (event) {
            case OrderEvent.OrderPlaced p -> "OrderPlaced";
            case OrderEvent.PaymentCaptured p -> "PaymentCaptured";
            case OrderEvent.OrderCancelled c -> "OrderCancelled";
        };
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }
}