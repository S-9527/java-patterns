package demo.concurrency;

/**
 * 方案 2：InheritableThreadLocal —— <b>子线程自动继承，但线程池会泄漏</b>。
 *
 * <p>问题：
 * <ul>
 *   <li>线程池复用时，子线程会继承上一个请求的上下文</li>
 *   <li>和 ThreadLocal 一样需要手动 remove()</li>
 *   <li>和虚拟线程完全不兼容（虚拟线程不继承 InheritableThreadLocal）</li>
 * </ul>
 */
public final class InheritableContext {

    private static final ThreadLocal<TraceContext> CONTEXT =
            new InheritableThreadLocal<>();

    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    /** ⚠ 必须手动调用。 */
    public static void remove() {
        CONTEXT.remove();
    }

    public static boolean isBound() {
        return CONTEXT.get() != null;
    }
}
