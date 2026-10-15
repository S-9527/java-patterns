package demo.concurrency;

/**
 * 方案 1：ThreadLocal —— <b>经典但危险</b>。
 *
 * <p>问题：
 * <ul>
 *   <li>必须手动 {@code remove()}，忘了就是内存泄漏</li>
 *   <li>虚拟线程是轻量级的，ThreadLocal 会让每个虚拟线程都占堆外内存</li>
 *   <li>子线程不自动继承（InheritableThreadLocal 会泄漏到线程池）</li>
 * </ul>
 */
public final class ThreadLocalContext {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    /** ⚠ 必须手动调用 —— 忘了就是内存泄漏。 */
    public static void remove() {
        CONTEXT.remove();
    }

    public static boolean isBound() {
        return CONTEXT.get() != null;
    }
}
