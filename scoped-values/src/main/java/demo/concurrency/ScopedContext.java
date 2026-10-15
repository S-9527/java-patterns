package demo.concurrency;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 方案 3：ScopedValue —— <b>不可变 + 自动作用域 + 不需要 remove()</b>。
 *
 * <p>核心优势：
 * <ul>
 *   <li><b>不可变</b> —— 一旦绑定，作用域内无法修改</li>
 *   <li><b>自动作用域</b> —— {@code where(...).run(...)} 结束后自动清理</li>
 *   <li><b>无内存泄漏</b> —— 虚拟线程上不需要 remove()</li>
 *   <li><b>明确传播</b> —— 子线程必须显式 {@code ScopedValue.where()} 传入</li>
 * </ul>
 *
 * <p>与 ThreadLocal 的关键区别：
 * <ul>
 *   <li>ThreadLocal：值存在线程上，作用域结束不清理，必须手动 remove</li>
 *   <li>ScopedValue：值绑定到作用域，作用域结束自动清理</li>
 * </ul>
 */
public final class ScopedContext {

    /** 三个独立的 ScopedValue —— traceId、customerId、tenant。 */
    public static final ScopedValue<TraceContext> CONTEXT = ScopedValue.newInstance();

    /**
     * 在给定上下文中运行。
     *
     * <p>注意：ScopedValue 的绑定是<b>不可变的</b> ——
     * 在作用域内不能再 {@code where()} 覆盖同一个 ScopedValue。
     */
    public static void run(TraceContext ctx, Runnable action) {
        ScopedValue.where(CONTEXT, ctx).run(action);
    }

    /**
     * 在给定上下文中运行并返回结果。
     */
    public static <T> T call(TraceContext ctx, java.util.concurrent.Callable<T> action)
            throws Exception {
        var holder = new java.util.concurrent.atomic.AtomicReference<T>();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        ScopedValue.where(CONTEXT, ctx).run(() -> {
            try {
                holder.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            throw error.get() instanceof Exception e ? e : new RuntimeException(error.get());
        }
        return holder.get();
    }

    /**
     * 在给定上下文中启动虚拟线程。
     *
     * <p>⚠ 关键点：虚拟线程<b>不会</b>自动继承父线程的 ScopedValue。
     * 必须在 {@code ScopedValue.where()} 的作用域内启动子虚拟线程，
     * 子虚拟线程才能通过 {@code ScopedValue.where().run()} 显式传播。
     *
     * <p>实际上，ScopedValue 的传播方式更简单：
     * 只要子虚拟线程在父线程的 {@code where().run()} 作用域内启动，
     * 子虚拟线程就能看到父线程的 ScopedValue 绑定。
     */
    public static Thread startVirtual(TraceContext ctx, Runnable action) {
        return Thread.ofVirtual().start(() ->
                ScopedValue.where(CONTEXT, ctx).run(action));
    }

    public static TraceContext get() {
        if (!CONTEXT.isBound()) {
            throw new IllegalStateException(
                    "TraceContext 未绑定 —— 必须在 ScopedValue.where(CONTEXT, ctx) 作用域内调用");
        }
        return CONTEXT.get();
    }

    public static boolean isBound() {
        return CONTEXT.isBound();
    }

    /** 测试用：在作用域外读取，不抛异常。 */
    public static TraceContext getOrNull() {
        return CONTEXT.isBound() ? CONTEXT.get() : null;
    }
}
