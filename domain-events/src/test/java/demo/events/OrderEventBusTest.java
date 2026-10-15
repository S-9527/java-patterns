package demo.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("领域事件 —— 观察者模式")
class OrderEventBusTest {

    private static final Currency CNY = Currency.getInstance("CNY");

    private static OrderEvent.OrderPlaced placed() {
        return new OrderEvent.OrderPlaced("SO-1", "U-1", 2, "T0");
    }

    private static OrderEvent.PaymentCaptured paid() {
        return new OrderEvent.PaymentCaptured("SO-1", new BigDecimal("268.00"), CNY, "ALIPAY", "T1");
    }

    private static OrderEventBus busWith(String... names) {
        var bus = new OrderEventBus();
        for (var n : names) {
            bus.on(n, e -> {
            });
        }
        return bus;
    }

    @Test
    @DisplayName("所有监听器都收到事件")
    void allListenersCalled() {
        var received = new ArrayList<String>();
        var bus = new OrderEventBus()
                .on("加积分", e -> received.add("积分"))
                .on("写日志", e -> received.add("日志"))
                .on("风控", e -> received.add("风控"));

        var results = bus.publish(placed());

        assertEquals(List.of("积分", "日志", "风控"), received, "按注册顺序全部调用");
        assertEquals(3, results.size());
        results.forEach(r -> assertInstanceOfOk(r));
    }

    @Test
    @DisplayName("⚠ 监听器抛异常被隔离，不影响其他监听器")
    void listenerFailureIsIsolated() {
        var received = new ArrayList<String>();
        var bus = new OrderEventBus()
                .on("加积分", e -> received.add("积分"))
                .on("发短信", e -> {
                    throw new RuntimeException("短信网关超时");
                })
                .on("写日志", e -> received.add("日志"));

        var results = bus.publish(placed());     // 不抛异常

        assertEquals(List.of("积分", "日志"), received,
                "发短信挂了，但排在它后面的写日志照样执行了 —— 这是隔离的关键");
        assertEquals(3, results.size());
        assertEquals(1, results.stream().filter(r -> r instanceof DispatchResult.Failed).count());
    }

    @Test
    @DisplayName("⚠ 开启 propagateFailures 后异常穿透 —— 这就是默认不这么干的原因")
    void propagateModeThrows() {
        var bus = new OrderEventBus()
                .on("发短信", e -> {
                    throw new RuntimeException("短信网关超时");
                })
                .propagateFailures(true);

        var e = assertThrows(OrderEventBus.ListenerFailedException.class,
                () -> bus.publish(paid()));
        assertEquals("监听器 发短信 失败", e.getMessage());
    }

    @Test
    @DisplayName("Failed 分可重试和不可重试")
    void retryAdvice() {
        var retryable = new DispatchResult.Failed("发短信", "连接超时", true);
        var fatal = new DispatchResult.Failed("发短信", "用户已被拉黑", false);

        assertEquals("应重试", retryable.advice());
        assertEquals("重试无用，应告警", fatal.advice());

        var summary = DispatchResult.summarize(List.of(retryable, fatal));
        assertTrue(summary.contains("应重试"));
        assertTrue(summary.contains("重试无用，应告警"));
        assertTrue(summary.contains("2 个失败"));
    }

    @Test
    @DisplayName("可重试判定：超时和 IO 算可重试，业务异常不算")
    void retryabilityClassification() throws Exception {
        // throws Exception 是必要的：测试要造 SocketTimeoutException 等受检异常，
        // 而它们不继承 RuntimeException
        var bus = new OrderEventBus();

        // ⚠ 这是本场景最关键的一条断言。
        //
        // Demo 里的"短信网关超时"用的是 RuntimeException，结果被判成不可重试 —— 错的。
        // 这里证明：只要监听器真的抛 SocketTimeoutException / IOException，判定就是对的。
        //
        // 能做到这一点的前提是 Listener.onEvent 声明了 throws Exception，
        // 否则这些受检异常必须被包成 RuntimeException，isRetryable 就永远判不出来。
        var timeout = registeredThatThrows("x",
                new java.net.SocketTimeoutException("t")).publish(paid());
        assertTrue(((DispatchResult.Failed) timeout.getFirst()).retryable(),
                "SocketTimeoutException 必须判为可重试 —— Demo 里的 RuntimeException 判错了");

        var io = registeredThatThrows("x", new java.io.IOException("io")).publish(paid());
        assertTrue(((DispatchResult.Failed) io.getFirst()).retryable(), "IOException 必须判为可重试");

        var blacklisted = registeredThatThrows("x",
                new OrderEventBus.BlacklistedException("已拉黑")).publish(paid());
        assertFalse(((DispatchResult.Failed) blacklisted.getFirst()).retryable(),
                "用户被拉黑，重试一万次也没用");

        // 反例：包成 RuntimeException 之后就判不出来了
        var wrapped = registeredThatThrows("x",
                new RuntimeException(new java.net.SocketTimeoutException("t"))).publish(paid());
        assertFalse(((DispatchResult.Failed) wrapped.getFirst()).retryable(),
                "包成 RuntimeException 后就丢了类型信息 —— 这正是不该包起来的原因");
    }

    @Test
    @DisplayName("批量汇总的三种情况")
    void summarizeCases() {
        assertEquals("没有监听器", DispatchResult.summarize(List.of()));

        // ⚠ List.of 会推断出 List<Ok> 而不是 List<DispatchResult>，
        // 直接传给 summarize(List<DispatchResult>) 会编译不过 —— 必须显式声明接口类型。
        List<DispatchResult> allOk = List.of(
                new DispatchResult.Ok("a", "x"), new DispatchResult.Ok("b", "y"));
        assertEquals("2 个全部成功", DispatchResult.summarize(allOk));

        List<DispatchResult> mixed = List.of(
                new DispatchResult.Ok("a", "x"),
                new DispatchResult.Failed("b", "超时", true));
        assertEquals("1 个成功，1 个失败（应重试）", DispatchResult.summarize(mixed));
    }

    @Test
    @DisplayName("dispatchAdvice 用无 default 的 sealed switch 逐条列出")
    void dispatchAdviceListsEveryResult() {
        var text = DispatchResult.dispatchAdvice(List.of(
                new DispatchResult.Ok("加积分", "已加 268 分"),
                new DispatchResult.Failed("发短信", "超时", true),
                new DispatchResult.Failed("风控", "黑名单", false)));

        assertTrue(text.contains("✓ 加积分"));
        assertTrue(text.contains("✗ 发短信"));
        assertTrue(text.contains("应重试"));
        assertTrue(text.contains("重试无用，应告警"));
    }

    @Test
    @DisplayName("顺序保证：按注册顺序串行调用")
    void orderIsDeterministic() {
        var bus = busWith("a", "b", "c", "d");
        var results = bus.publish(placed());

        assertEquals(List.of("a", "b", "c", "d"),
                results.stream()
                        .map(r -> ((DispatchResult.Ok) r).listener())
                        .toList());
    }

    @Test
    @DisplayName("已发布的事件被记录，可回溯")
    void publishedEventsAreRecorded() {
        var bus = busWith("a");
        bus.publish(placed());
        bus.publish(paid());
        bus.publish(new OrderEvent.OrderCancelled("SO-1", "超时", "T2"));

        assertEquals(3, bus.publishedEvents().size());
    }

    @Test
    @DisplayName("⚠ 观察者模式的固有弱点：退订要调用方自己拿 bus 引用")
    void unsubscribingRequiresExternalReference() {
        var bus = busWith("a", "b");
        assertEquals(2, bus.listenerCount());

        // 这就是问题所在：发布方（OrderService）不认识监听器，
        // 退订时必须由监听器自己持有 bus 引用并调用 on(...) 注册的反向操作。
        // 本项目刻意没提供 remove() —— 真实的 EventBus 大多也不提供。
        System.out.println("        提示：OrderEventBus 没有 remove 方法");
    }

    @Test
    @DisplayName("sealed switch 分组 —— 加事件类型编译不过")
    void sealedSwitchGroupsEvents() {
        var counts = new java.util.EnumMap<Kind, Integer>(Kind.class);
        for (var e : List.of(placed(), paid(), paid(), new OrderEvent.OrderCancelled("SO-1", "x", "T2"))) {
            counts.merge(kindOf(e), 1, Integer::sum);
        }

        assertEquals(1, counts.get(Kind.PLACED));
        assertEquals(2, counts.get(Kind.PAID));
        assertEquals(1, counts.get(Kind.CANCELLED));
    }

    private enum Kind { PLACED, PAID, CANCELLED }

    private static Kind kindOf(OrderEvent e) {
        return switch (e) {                     // 无 default
            case OrderEvent.OrderPlaced _ -> Kind.PLACED;
            case OrderEvent.PaymentCaptured _ -> Kind.PAID;
            case OrderEvent.OrderCancelled _ -> Kind.CANCELLED;
        };
    }

    private static OrderEventBus registeredThatThrows(String name, Exception toThrow) {
        // ThrowingConsumer 允许抛受检异常，所以这里不用包 RuntimeException 也不用 sneaky throw
        return new OrderEventBus().on(name, e -> {
            throw toThrow;
        });
    }

    private static void assertInstanceOfOk(DispatchResult r) {
        assertTrue(r instanceof DispatchResult.Ok,
                "期望成功，实际 " + r);
    }
}