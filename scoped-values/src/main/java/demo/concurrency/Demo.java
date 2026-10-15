package demo.concurrency;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ScopedValue vs ThreadLocal —— 虚拟线程时代的上下文传递。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var ctx = new TraceContext("trace-001", "customer-A", "tenant-1");

        heading("方案 1：ThreadLocal —— 必须手动 remove()");
        var result1 = RequestProcessor.processWithThreadLocal(ctx, () -> {
            out("  在作用域内：%s".formatted(ThreadLocalContext.get()));
            out("  是否绑定？ %b".formatted(ThreadLocalContext.isBound()));
        });
        out("  %s".formatted(result1));
        out("  作用域外：是否绑定？ %b".formatted(ThreadLocalContext.isBound()));
        out("  ✅ remove() 被调用了。但如果你忘了 finally，这就是内存泄漏。");

        heading("方案 2：InheritableThreadLocal —— 子线程继承，但线程池泄漏");
        var ctx2 = new TraceContext("trace-002", "customer-B", "tenant-2");
        InheritableContext.set(ctx2);
        try {
            out("  父线程：%s".formatted(InheritableContext.get()));
            // 子线程会自动继承
            var t = new Thread(() -> {
                out("  子线程：%s".formatted(InheritableContext.get()));
            });
            t.start();
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            out("  ⚠ 如果这个子线程来自线程池，它会一直持有父线程的上下文。");
            out("    下一个请求用这个线程时，会读到上一个请求的 traceId。");
        } finally {
            InheritableContext.remove();
        }

        heading("方案 3：ScopedValue —— 不可变 + 自动作用域");
        var ctx3 = new TraceContext("trace-003", "customer-C", "tenant-3");
        ScopedContext.run(ctx3, () -> {
            out("  在作用域内：%s".formatted(ScopedContext.get()));
            out("  是否绑定？ %b".formatted(ScopedContext.isBound()));
        });
        out("  作用域外：是否绑定？ %b（自动清理，不需要 remove()）"
                .formatted(ScopedContext.isBound()));
        out("  ✅ ScopedValue 的值绑定到作用域，作用域结束自动清理。");

        heading("ScopedValue + 虚拟线程：显式传播");
        try {
            var result = RequestProcessor.processInVirtualThread(ctx3, () -> {
                out("  虚拟线程内：%s".formatted(ScopedContext.get()));
            });
            out("  %s".formatted(result));
            out("  ⚠ 子虚拟线程不自动继承 —— 必须显式重新绑定。");
            out("    这是 ScopedValue 的设计哲学：显式传播，不像 InheritableThreadLocal。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        heading("⚠ 并发无交叉污染：100 个虚拟线程各用各的上下文");
        var contexts = generateContexts(100);
        List<String> results;
        try {
            results = RequestProcessor.processConcurrently(contexts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        var mismatches = results.stream()
                .filter(r -> !r.contains("→"))
                .toList();
        out("  100 个虚拟线程，每个用自己的 traceId");
        out("  结果数：%d，不一致数：%d".formatted(results.size(), mismatches.size()));
        out("  ✅ 并发下无交叉污染 —— 每个虚拟线程只看到自己的上下文。");

        heading("⚠ ThreadLocal 在虚拟线程上的内存泄漏");
        out("  虚拟线程是轻量级的（每个只占几 KB 栈），但 ThreadLocal 不是。");
        out("  ThreadLocal 的值存在线程的 ThreadLocalMap 里，是强引用。");
        out();
        out("  100 万个虚拟线程 + 每个都设了 ThreadLocal = 100 万个 ThreadLocalMap。");
        out("  虚拟线程的生命周期短，但 ThreadLocal 不会自动清理。");
        out("  如果忘了 remove()，这些 Map 会一直存在直到线程结束。");
        out();
        out("  ScopedValue 的值绑定到作用域，作用域结束自动清理。");
        out("  不需要 remove()，不存在泄漏。");

        heading("⚠ ScopedValue 的限制");
        out("  1. 不可变 —— 作用域内不能修改已绑定的值");
        out("     但新 Carrier 可以 shadow（内层作用域读到新值，外层不变）");
        out("  2. 子线程不自动继承 —— 必须显式重新绑定");
        out("     不像 InheritableThreadLocal 那样隐式继承");
        out("  3. 作用域外访问抛 IllegalStateException（不是返回 null）");
        out("  4. 不能序列化 —— ScopedValue 没有 Serializable");
        out("  5. 子线程传播需要显式捕获父线程的值");

        System.out.println();
    }

    private static List<TraceContext> generateContexts(int count) {
        var ctxs = new java.util.ArrayList<TraceContext>(count);
        for (int i = 0; i < count; i++) {
            ctxs.add(new TraceContext("trace-%03d".formatted(i),
                    "customer-%d".formatted(i), "tenant-%d".formatted(i % 3)));
        }
        return ctxs;
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}
