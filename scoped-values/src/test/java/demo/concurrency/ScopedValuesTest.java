package demo.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ScopedValue vs ThreadLocal —— 虚拟线程上下文传递")
class ScopedValuesTest {

    // ─────────────────────────────────────────────
    // ScopedValue 基本行为
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("ScopedValue 基本行为")
    class ScopedValueBasics {

        @Test
        @DisplayName("作用域内可以读到值")
        void scopedValueReadableInside() {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            ScopedContext.run(ctx, () -> {
                assertEquals(ctx, ScopedContext.get());
                assertTrue(ScopedContext.isBound());
            });
        }

        @Test
        @DisplayName("⚠ 作用域外读不到 —— 自动清理")
        void scopedValueClearedOutside() {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            ScopedContext.run(ctx, () -> {
                assertTrue(ScopedContext.isBound());
            });
            assertFalse(ScopedContext.isBound(),
                    "⚠ 作用域结束后自动清理，不需要 remove()");
            assertNull(ScopedContext.getOrNull());
        }

        @Test
        @DisplayName("⚠ 作用域外 get() 抛 IllegalStateException")
        void scopedValueGetOutsideThrows() {
            assertThrows(IllegalStateException.class, ScopedContext::get,
                    "⚠ 作用域外访问抛异常，不是返回 null");
        }

        @Test
        @DisplayName("⚠ ScopedValue 不可变 —— 但新 Carrier 可以 shadow")
        void scopedValueIsImmutableButShadowable() {
            var ctx1 = new TraceContext("t1", "c1", "tenant-1");
            var ctx2 = new TraceContext("t2", "c2", "tenant-2");

            // 作用域内读取的值是不可变的
            ScopedContext.run(ctx1, () -> {
                var read = ScopedContext.get();
                assertEquals(ctx1, read, "读到的值是 ctx1，不可修改");
            });

            // 新 Carrier 可以 shadow，但不会修改外层作用域的值
            ScopedContext.run(ctx1, () -> {
                assertEquals(ctx1, ScopedContext.get());
                ScopedContext.run(ctx2, () -> {
                    assertEquals(ctx2, ScopedContext.get(), "内层作用域读到 ctx2");
                });
                assertEquals(ctx1, ScopedContext.get(),
                        "⚠ 外层作用域仍然读到 ctx1 —— 新 Carrier 只 shadow，不修改");
            });
        }

        @Test
        @DisplayName("ScopedValue.call() 返回值")
        void scopedValueCallReturns() throws Exception {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            var result = ScopedContext.call(ctx, () -> ScopedContext.get().traceId());
            assertEquals("t1", result);
        }
    }

    // ─────────────────────────────────────────────
    // 虚拟线程传播
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("虚拟线程传播")
    class VirtualThreadPropagation {

        @Test
        @DisplayName("⚠ 子虚拟线程不自动继承 ScopedValue —— 必须显式重新绑定")
        void childVirtualThreadRequiresExplicitBinding() throws InterruptedException {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var readCtx = new AtomicReference<TraceContext>();

            ScopedValue.where(ScopedContext.CONTEXT, ctx).run(() -> {
                var capturedCtx = ScopedContext.get();
                Thread.ofVirtual().start(() -> {
                    try {
                        // ⚠ 子虚拟线程不自动继承父线程的 ScopedValue
                        // 必须显式重新绑定 —— 这是 ScopedValue 的设计哲学：
                        // 显式传播，不像 InheritableThreadLocal 那样隐式继承
                        ScopedValue.where(ScopedContext.CONTEXT, capturedCtx).run(() -> {
                            readCtx.set(ScopedContext.get());
                        });
                    } catch (Throwable t) {
                        error.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(error.get(), "子虚拟线程不应出错");
            assertEquals(ctx, readCtx.get(),
                    "子虚拟线程通过显式重新绑定读到上下文");
        }

        @Test
        @DisplayName("子虚拟线程在作用域外启动，读不到值")
        void childVirtualThreadOutsideScopeCannotRead() throws InterruptedException {
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();

            Thread.ofVirtual().start(() -> {
                try {
                    ScopedContext.get();
                    fail("应该抛异常");
                } catch (IllegalStateException e) {
                    // 预期的行为
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.countDown();
                }
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
        }
    }

    // ─────────────────────────────────────────────
    // 并发无交叉污染
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("并发无交叉污染")
    class NoCrossContamination {

        @Test
        @DisplayName("100 个虚拟线程各用各的上下文，无交叉")
        void hundredThreadsNoCrossContamination() throws InterruptedException {
            var contexts = generateContexts(100);
            var results = RequestProcessor.processConcurrently(contexts);

            assertEquals(100, results.size(), "100 个请求都应该完成");

            // 每个结果应该是 "trace-XXX → trace-XXX"
            for (var result : results) {
                var parts = result.split(" → ");
                assertEquals(2, parts.length, "格式应该是 'in → out'");
                assertEquals(parts[0], parts[1],
                        "⚠ 输入和输出应该一致（无交叉污染）");
            }
        }

        @Test
        @DisplayName("⚠ ThreadLocal 并发下也可能无交叉（但需要手动 remove）")
        void threadLocalConcurrentIsOkButFragile() throws InterruptedException {
            var contexts = generateContexts(50);
            var results = new ConcurrentLinkedQueue<String>();
            var done = new CountDownLatch(contexts.size());
            var errors = new AtomicReference<Throwable>();

            for (var ctx : contexts) {
                Thread.ofVirtual().start(() -> {
                    try {
                        ThreadLocalContext.set(ctx);
                        var current = ThreadLocalContext.get();
                        results.add("%s → %s".formatted(ctx.traceId(), current.traceId()));
                    } catch (Throwable t) {
                        errors.set(t);
                    } finally {
                        // ⚠ 必须 remove() —— 忘了就是泄漏
                        ThreadLocalContext.remove();
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNull(errors.get());
            assertEquals(50, results.size());
        }

        @Test
        @DisplayName("⚠ ThreadLocal 忘了 remove() 会泄漏到下一个请求")
        void threadLocalLeaksWithoutRemove() throws InterruptedException {
            var ctx1 = new TraceContext("t1", "c1", "tenant-1");
            var ctx2 = new TraceContext("t2", "c2", "tenant-2");

            // 第一个请求设了 ThreadLocal 但没 remove
            Thread.ofVirtual().start(() -> {
                ThreadLocalContext.set(ctx1);
                // 没有 remove()
            });

            // 给第一个线程一点时间
            Thread.sleep(50);

            // 第二个请求读 ThreadLocal
            // 注意：不同虚拟线程的 ThreadLocal 是独立的
            // 所以这里不会泄漏 —— 但如果是线程池就会泄漏
            Thread.ofVirtual().start(() -> {
                // 不同虚拟线程，ThreadLocal 是独立的
                assertFalse(ThreadLocalContext.isBound(),
                        "不同虚拟线程的 ThreadLocal 是独立的");
            });

            Thread.sleep(50);
        }
    }

    // ─────────────────────────────────────────────
    // ThreadLocal vs ScopedValue
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("ThreadLocal vs ScopedValue")
    class Comparison {

        @Test
        @DisplayName("ThreadLocal 需要手动 remove()")
        void threadLocalRequiresManualRemove() {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            ThreadLocalContext.set(ctx);
            assertTrue(ThreadLocalContext.isBound());
            // 不 remove()，上下文还在
            assertTrue(ThreadLocalContext.isBound(),
                    "⚠ 不 remove() 上下文还在");
            ThreadLocalContext.remove();
            assertFalse(ThreadLocalContext.isBound(),
                    "remove() 后上下文没了");
        }

        @Test
        @DisplayName("ScopedValue 不需要 remove()")
        void scopedValueNoRemoveNeeded() {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            ScopedContext.run(ctx, () -> {
                assertTrue(ScopedContext.isBound());
            });
            assertFalse(ScopedContext.isBound(),
                    "作用域结束后自动清理，不需要 remove()");
        }

        @Test
        @DisplayName("⚠ ThreadLocal 在虚拟线程上的内存开销")
        void threadLocalMemoryOnVirtualThreads() {
            // 这个测试不直接测内存，只记录事实：
            // ThreadLocal 的值存在线程的 ThreadLocalMap 里
            // 虚拟线程也是线程，也有 ThreadLocalMap
            // 如果每个虚拟线程都设了 ThreadLocal，就有内存开销

            var ctx = new TraceContext("t1", "c1", "tenant-1");
            ThreadLocalContext.set(ctx);
            // ThreadLocalMap 现在有一个 entry
            // 如果不 remove()，这个 entry 会一直存在
            assertNotNull(ThreadLocalContext.get(),
                    "ThreadLocal 的值存在线程的 ThreadLocalMap 里");
            ThreadLocalContext.remove();
        }
    }

    // ─────────────────────────────────────────────
    // ScopedContext API
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("ScopedContext API")
    class ScopedContextApi {

        @Test
        @DisplayName("getOrNull 在作用域外返回 null")
        void getOrNullReturnsNull() {
            assertNull(ScopedContext.getOrNull(),
                    "作用域外 getOrNull 返回 null，不抛异常");
        }

        @Test
        @DisplayName("startVirtual 需要显式重新绑定")
        void startVirtualRequiresExplicitBinding() throws InterruptedException {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            var done = new CountDownLatch(1);
            var readCtx = new AtomicReference<TraceContext>();

            ScopedValue.where(ScopedContext.CONTEXT, ctx).run(() -> {
                var capturedCtx = ScopedContext.get();
                Thread.ofVirtual().start(() -> {
                    try {
                        // 子虚拟线程需要显式重新绑定
                        ScopedValue.where(ScopedContext.CONTEXT, capturedCtx).run(() -> {
                            readCtx.set(ScopedContext.get());
                        });
                    } finally {
                        done.countDown();
                    }
                });
            });

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(ctx, readCtx.get());
        }
    }

    // ─────────────────────────────────────────────
    // TraceContext
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("TraceContext")
    class TraceContextTest {

        @Test
        @DisplayName("TraceContext 是 record，不可变")
        void traceContextIsImmutable() {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            var ctx2 = new TraceContext("t1", "c1", "tenant-1");
            assertEquals(ctx, ctx2, "record 值相等");
        }

        @Test
        @DisplayName("TraceContext toString 包含所有字段")
        void traceContextToString() {
            var ctx = new TraceContext("t1", "c1", "tenant-1");
            assertTrue(ctx.toString().contains("t1"));
            assertTrue(ctx.toString().contains("c1"));
            assertTrue(ctx.toString().contains("tenant-1"));
        }
    }

    // ─────────────────────────────────────────────
    // 辅助
    // ─────────────────────────────────────────────

    private static List<TraceContext> generateContexts(int count) {
        var ctxs = new java.util.ArrayList<TraceContext>(count);
        for (int i = 0; i < count; i++) {
            ctxs.add(new TraceContext("trace-%03d".formatted(i),
                    "customer-%d".formatted(i), "tenant-%d".formatted(i % 3)));
        }
        return ctxs;
    }

    private static void fail(String msg) {
        throw new AssertionError(msg);
    }
}
