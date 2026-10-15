package demo.events;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件总线 —— 观察者模式。
 *
 * <p><b>核心决策：监听器抛异常被隔离成 {@link DispatchResult.Failed}，
 * 不向上传播。</b>
 *
 * <p>观察者模式最容易出的事故：某个监听器（比如发短信）挂了，把整个业务流程带崩。
 * 用户支付成功了，结果因为短信网关超时而回滚 —— 这是不可接受的。
 *
 * <p>但完全吞掉异常也不行。所以这里<b>捕获、隔离、然后如实上报</b>，
 * 让调用方决定要不要告警。
 */
public final class OrderEventBus {

    /**
     * 一个监听器。<b>故意不标 {@code @FunctionalInterface}</b> ——
     * 它有两个抽象方法（{@code name} 和 {@code onEvent}），不是函数式接口。
     *
     * <p>想要 lambda 就用 {@link #on(String, Consumer)}，那个重载帮你把名字补上。
     *
     * <p>「让监听器自己声明名字」是个真实的两难：
     * 不要求名字，报错时只能说"某个监听器失败"；要求名字，就没法直接写 lambda。
     * 这里选了后者 —— 报错必须能指名道姓，这比写起来方便更重要。
     */
    public interface Listener {
        String name();

        /**
         * 允许抛受检异常。
         *
         * <p>这不是偷懒：短信网关超时（{@code SocketTimeoutException}）和 IO 错误
         * 都是受检异常，不继承 {@code RuntimeException}。如果接口只允许抛
         * {@code RuntimeException}，这两个最该重试的异常就必须被包起来，
         * {@link #isRetryable} 就再也区分不出来了。
         */
        void onEvent(OrderEvent event) throws Exception;
    }

    /** 允许抛受检异常的消费者。 */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    private final List<Listener> listeners = new ArrayList<>();

    /** 监听器执行失败时是否向上传播。默认 false（隔离）。 */
    private boolean propagateFailures = false;

    private final List<OrderEvent> published = new ArrayList<>();

    public OrderEventBus on(Listener listener) {
        listeners.add(listener);
        return this;
    }

    /**
     * 用 lambda 注册，把名字一并给出。
     *
     * <p>参数是 {@link ThrowingConsumer} 而不是 {@code Consumer} ——
     * 因为 {@code Consumer.accept} 不声明受检异常，lambda 里就没法直接
     * {@code throw new IOException(...)}。而"发短信超时"恰恰是受检异常。
     *
     * <p>代价是 lambda 不能省掉异常处理，但这比在真实场景里被迫包
     * {@code RuntimeException} 更安全 —— {@link #isRetryable} 能区分
     * 受检异常和 {@link BlacklistedException}，前提是它们别被包起来。
     */
    public OrderEventBus on(String name, ThrowingConsumer<OrderEvent> action) {
        return on(new Listener() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void onEvent(OrderEvent event) throws Exception {
                action.accept(event);
            }
        });
    }

    public OrderEventBus propagateFailures(boolean value) {
        this.propagateFailures = value;
        return this;
    }

    public int listenerCount() {
        return listeners.size();
    }

    public List<OrderEvent> publishedEvents() {
        return List.copyOf(published);
    }

    /**
     * 发布事件，返回每个监听器的结果。
     *
     * <p><b>顺序保证</b>：按注册顺序串行调用。这让"先扣库存再发通知"这类依赖可预测。
     * 如果需要并发分发，那是另一个设计问题（要讨论顺序、线程安全、部分失败重试）。
     */
    public List<DispatchResult> publish(OrderEvent event) {
        published.add(event);
        var results = new ArrayList<DispatchResult>();

        for (var listener : listeners) {
            try {
                listener.onEvent(event);
                results.add(new DispatchResult.Ok(listener.name(), summaryOf(event)));
            } catch (Exception e) {
                // catch Exception 而不是 RuntimeException：
                // SocketTimeoutException / IOException 都是受检异常，不继承 RuntimeException，
                // 而它们恰恰是最该重试的那一类。只 catch RuntimeException 会漏掉它们。
                if (propagateFailures) {
                    // 另一个极端：让异常穿透，发布失败
                    throw new ListenerFailedException(listener.name(), e);
                }
                // 默认：隔离，记一笔 Failed，继续下一个监听器
                results.add(new DispatchResult.Failed(listener.name(), e.getMessage(),
                        isRetryable(e)));
            }
        }
        return List.copyOf(results);
    }

    /**
     * 判断该不该重试。
     *
     * <p>这是个业务决策，不是技术判断。这里用异常类型做粗粒度区分，
     * 真实系统会看错误码 / 异常子类 / HTTP 状态码。
     */
    private static boolean isRetryable(Exception e) {
        // ⚠ 这里必须用 instanceof 链而不是模式匹配 switch：
        // 异常类不是 sealed 的（RuntimeException 有一大堆未密封的子类），
        // 所以编译器不可能保证穷尽，写了 default 也没意义 —— 那还不如显式写清楚。
        //
        // 用 instanceof 而不是"异常类名包含 Timeout"这种字符串匹配，
        // 是为了让这个判断在重构时保持编译期安全。
        if (e instanceof java.net.SocketTimeoutException) {
            return true;
        }
        if (e instanceof java.io.IOException) {
            return true;
        }
        if (e instanceof BlacklistedException) {
            return false;       // 用户被拉黑，重试一万次也没用
        }
        return false;
    }

    /**
     * 上面这个分类<b>不完整</b> —— Demo 里那个"短信网关超时"用的是
     * {@code RuntimeException}，所以被判成不可重试，输出里写的是"重试无用，应告警"。
     *
     * <p>真实系统不应该这样。原因写在 {@link ThrowingConsumer} 的 javadoc 里：
     * 监听器接口允许抛受检异常，超时和 IO 错误才会原样冒出来，
     * 上面的 {@code instanceof} 才判得对。Demo 为了简洁才用了 RuntimeException。
     *
     * <p>这也是一个教训：<b>"该重试还是该告警"是业务决策，分类依据必须显式且正确</b>，
     * 不能靠异常继承体系碰运气。
     */

    /** 用 sealed switch 生成一行摘要 —— 穷尽性由编译器保证。 */
    private static String summaryOf(OrderEvent event) {
        return switch (event) {
            case OrderEvent.OrderPlaced(String orderId, String customerId, int itemCount, String at) ->
                    "已下单 %s，%d 件".formatted(orderId, itemCount);
            case OrderEvent.PaymentCaptured(String orderId, java.math.BigDecimal amount,
                                            java.util.Currency cur, String channel, String at) ->
                    "已收款 %s %s（%s）".formatted(cur.getCurrencyCode(), amount, channel);
            case OrderEvent.OrderCancelled(String orderId, String reason, String at) ->
                    "已取消 %s：%s".formatted(orderId, reason);
        };
    }

    /** 抛这个表示"用户被拉黑"，重试无意义。 */
    public static final class BlacklistedException extends RuntimeException {
        public BlacklistedException(String message) {
            super(message);
        }
    }

    /** 只在 propagateFailures 模式下抛。 */
    public static final class ListenerFailedException extends RuntimeException {
        public ListenerFailedException(String listener, Throwable cause) {
            super("监听器 " + listener + " 失败", cause);
        }
    }
}