package demo.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 重试代理 —— 横切关注点的另一种用法。
 *
 * <p>策略：捕获 {@link java.lang.reflect.InvocationTargetException} 的 cause，
 * 只对可重试的异常重试。"可重试"用异常类名白名单判定，避免在接口上加注解
 * （那样调用方还是看不到重试逻辑）。
 *
 * <p><b>关键设计</b>：重试次数是 <b>每次调用</b> 独立计数的，
 * 不是全局计数器。否则一次失败就把配额耗尽。
 */
public final class RetryInvocationHandler implements InvocationHandler {

    private final Object target;
    private final int maxAttempts;
    private final java.util.function.Consumer<String> sink;
    private final AtomicLong totalAttempts = new AtomicLong();

    public RetryInvocationHandler(Object target, int maxAttempts,
                                  java.util.function.Consumer<String> sink) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("重试次数必须 >= 1：" + maxAttempts);
        }
        this.target = target;
        this.maxAttempts = maxAttempts;
        this.sink = sink;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            totalAttempts.incrementAndGet();
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                lastFailure = e.getCause();
                if (!isRetryable(lastFailure) || attempt == maxAttempts) {
                    break;
                }
                sink.accept("  ↻ %s 第 %d/%d 次失败：%s，重试".formatted(
                        method.getName(), attempt, maxAttempts, lastFailure.getMessage()));
            }
        }
        throw lastFailure;
    }

    /**
     * 可重试判定 —— 用类名白名单。
     *
     * <p>真实系统里这里通常是重试注解 + 异常分类器。这里用白名单是
     * 为了<b>不引入 AOP 依赖</b>（本项目的零第三方依赖约定）。
     */
    private static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        var cls = t.getClass().getName();
        // 瞬时故障类：网络、IO、超时
        return cls.contains("TimeoutException")
                || cls.contains("IOException")
                || cls.contains("ConnectException")
                || t instanceof IllegalStateException;
    }

    public long totalAttempts() {
        return totalAttempts.get();
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
