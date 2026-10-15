package demo.concurrency;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 请求处理器 —— 演示三种上下文传递方案的实际使用。
 */
public final class RequestProcessor {

    /**
     * 用 ThreadLocal 处理请求。
     *
     * <p>⚠ 关键问题：必须在 finally 里 remove()，否则内存泄漏。
     * 虚拟线程是轻量级的，但 ThreadLocal 是重量级的。
     */
    public static String processWithThreadLocal(TraceContext ctx, Runnable action) {
        ThreadLocalContext.set(ctx);
        try {
            action.run();
            return "完成：" + ctx;
        } finally {
            // ⚠ 必须 remove() —— 忘了就是内存泄漏
            ThreadLocalContext.remove();
        }
    }

    /**
     * 用 ScopedValue 处理请求。
     *
     * <p>✅ 不需要 remove() —— 作用域结束后自动清理。
     */
    public static String processWithScoped(TraceContext ctx, Runnable action) {
        ScopedContext.run(ctx, action);
        return "完成：" + ctx;
    }

    /**
     * 用 ScopedValue 在虚拟线程中处理请求。
     *
     * <p>子虚拟线程不自动继承父线程的 ScopedValue。
     * 必须在子线程中显式重新绑定。
     */
    public static String processInVirtualThread(TraceContext ctx, Runnable action)
            throws InterruptedException {
        var error = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);

        ScopedValue.where(ScopedContext.CONTEXT, ctx).run(() -> {
            var capturedCtx = ScopedContext.get();
            Thread.ofVirtual().start(() -> {
                try {
                    // ⚠ 子虚拟线程不自动继承 —— 必须显式重新绑定
                    ScopedValue.where(ScopedContext.CONTEXT, capturedCtx).run(action);
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.countDown();
                }
            });
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "虚拟线程应完成");
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        return "虚拟线程完成：" + ctx;
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new IllegalStateException(msg);
        }
    }

    /**
     * 用 ScopedValue 并发处理多个请求，验证无交叉污染。
     */
    public static List<String> processConcurrently(List<TraceContext> contexts)
            throws InterruptedException {
        var threadCount = contexts.size();
        var done = new CountDownLatch(threadCount);
        var errors = new AtomicReference<Throwable>();
        var results = new java.util.concurrent.ConcurrentLinkedQueue<String>();

        for (var ctx : contexts) {
            Thread.ofVirtual().start(() -> {
                try {
                    ScopedContext.run(ctx, () -> {
                        var current = ScopedContext.get();
                        results.add("%s → %s".formatted(ctx.traceId(), current.traceId()));
                    });
                } catch (Throwable t) {
                    errors.set(t);
                } finally {
                    done.countDown();
                }
            });
        }

        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发处理超时");
        }
        if (errors.get() != null) {
            throw new RuntimeException(errors.get());
        }
        return List.copyOf(results);
    }
}
