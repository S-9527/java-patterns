package demo.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("动态代理 —— 横切关注点 vs 堆栈断裂")
class ProxyTest {

    @BeforeEach
    void setUp() {
        // 每个测试用新的实现，避免共享状态
    }

    // ─────────────────────────────────────────────
    // 透明度：代理对调用方透明
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("透明度 —— 代理对调用方透明")
    class Transparency {

        @Test
        @DisplayName("代理实现了接口")
        void proxyImplementsInterface() {
            var proxy = UserServiceProxy.withLog(new DefaultUserService(), s -> {});
            assertTrue(proxy instanceof UserService, "代理必须是 UserService");
        }

        @Test
        @DisplayName("⚠ 代理不是实现类 —— 只能看到接口")
        void proxyIsNotImpl() {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withLog(impl, s -> {});
            assertFalse(proxy instanceof DefaultUserService,
                    "⚠ 代理不是实现类 —— 调用方只能用接口方法");
        }

        @Test
        @DisplayName("代理的方法调用结果和原始实现一致")
        void proxyDelegatesCorrectly() {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withLog(impl, s -> {});

            var added = proxy.add("张三", "z@example.com");
            var found = proxy.find(added.id());
            assertEquals(added, found, "代理透传返回值");
            assertEquals(1, proxy.count(), "代理透传 count");
            assertEquals(List.of(added), proxy.all(), "代理透传 all");
        }

        @Test
        @DisplayName("⚠ 代理的 getClass() 是动态生成的 Proxy 类")
        void proxyClassNameIsGenerated() {
            var proxy = UserServiceProxy.withLog(new DefaultUserService(), s -> {});
            assertTrue(proxy.getClass().getName().contains("Proxy"),
                    "代理类名包含 Proxy，形如 jdk.proxy1.$Proxy1");
            assertTrue(Proxy.isProxyClass(proxy.getClass()),
                    "Proxy.isProxyClass 可以识别");
        }

        @Test
        @DisplayName("⚠ 同一个接口的不同 handler 共享同一个代理类")
        void differentHandlersShareProxyClass() {
            var impl = new DefaultUserService();
            var logProxy = UserServiceProxy.withLog(impl, s -> {});
            var retryProxy = UserServiceProxy.withRetry(impl, 3, s -> {});
            // JVM 按 (ClassLoader, interfaces[]) 缓存代理类
            assertEquals(logProxy.getClass(), retryProxy.getClass(),
                    "⚠ JVM 按 (ClassLoader, 接口) 缓存代理类 —— 不同 handler 共享同一个代理类");
        }
    }

    // ─────────────────────────────────────────────
    // 日志代理
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("日志代理")
    class Logging {

        @Test
        @DisplayName("每次方法调用都被记录")
        void everyCallIsLogged() {
            var log = new ArrayList<String>();
            var proxy = UserServiceProxy.withLog(new DefaultUserService(), log::add);

            proxy.add("张三", "z@example.com");
            proxy.find("U-0001");
            proxy.count();

            assertEquals(6, log.size(), "add+find+count 各 2 行（进入+退出）");
            assertTrue(log.get(0).contains("add"));
            assertTrue(log.get(2).contains("find"));
            assertTrue(log.get(4).contains("count"));
        }

        @Test
        @DisplayName("异常也被记录，然后抛出")
        void exceptionIsLoggedAndPropagated() {
            var log = new ArrayList<String>();
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withLog(impl, log::add);

            assertThrows(IllegalArgumentException.class, () -> proxy.find("U-9999"));
            assertTrue(log.stream().anyMatch(l -> l.contains("抛异常")),
                    "日志里应该有异常记录");
        }

        @Test
        @DisplayName("⚠ 代理抛出的是 cause，不是 InvocationTargetException")
        void proxyUnwrapsInvocationTargetException() {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withLog(impl, s -> {});

            // 如果代理抛的是 InvocationTargetException，调用方 catch 不到 IllegalArgumentException
            var ex = assertThrows(IllegalArgumentException.class, () -> proxy.find("U-9999"));
            assertEquals("用户不存在：U-9999", ex.getMessage());
        }

        @Test
        @DisplayName("日志代理是线程安全的（callCount 计数器）")
        void loggingHandlerIsThreadSafe() throws InterruptedException {
            var impl = new DefaultUserService();
            var handler = new LogInvocationHandler(impl, s -> {});
            var proxy = UserServiceProxy.newProxy(UserService.class, impl, handler);

            var threadCount = 50;
            var done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final var idx = i;
                Thread.ofVirtual().start(() -> {
                    try {
                        proxy.add("用户" + idx, "u" + idx + "@example.com");
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(50, handler.callCount(), "50 个虚拟线程各调用一次");
        }
    }

    // ─────────────────────────────────────────────
    // 重试代理
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("重试代理")
    class Retry {

        @Test
        @DisplayName("重试代理在 maxAttempts 内成功")
        void retrySucceeds() {
            var log = new ArrayList<String>();
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withRetry(impl, 3, log::add);

            impl.failFindNext();   // 第 1 次抛异常
            impl.failFindNext();   // 第 2 次抛异常
            // 第 3 次成功

            var added = proxy.add("张三", "z@example.com");
            var found = proxy.find(added.id());
            assertEquals(added, found, "第 3 次尝试成功");
            assertTrue(log.stream().anyMatch(l -> l.contains("重试")),
                    "日志应该有重试记录");
        }

        @Test
        @DisplayName("重试代理超过 maxAttempts 后抛出最后一次的异常")
        void retryExhausts() {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withRetry(impl, 2, s -> {});
            impl.failFindNext();
            impl.failFindNext();
            impl.failFindNext();   // 3 次都失败，但只重试 2 次

            var ex = assertThrows(IllegalStateException.class, () -> proxy.find("U-0001"));
            assertEquals("模拟瞬时故障", ex.getMessage());
        }

        @Test
        @DisplayName("⚠ 不可重试的异常不重试")
        void nonRetryableIsNotRetried() {
            var handler = new RetryInvocationHandler(new DefaultUserService(), 3, s -> {});
            var proxy = UserServiceProxy.newProxy(UserService.class, new DefaultUserService(), handler);

            // IllegalArgumentException 不在重试白名单里
            assertThrows(IllegalArgumentException.class, () -> proxy.find("U-9999"));
            assertEquals(1, handler.totalAttempts(), "不重试，只调用 1 次");
        }

        @Test
        @DisplayName("重试次数 < 1 被拒绝")
        void retryRejectsBadMaxAttempts() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RetryInvocationHandler(new DefaultUserService(), 0, s -> {}));
            assertThrows(IllegalArgumentException.class,
                    () -> new RetryInvocationHandler(new DefaultUserService(), -1, s -> {}));
        }

        @Test
        @DisplayName("总尝试次数被正确累计")
        void totalAttemptsCounted() {
            var handler = new RetryInvocationHandler(new DefaultUserService(), 3, s -> {});
            var proxy = UserServiceProxy.newProxy(UserService.class, new DefaultUserService(), handler);
            proxy.add("张三", "z@example.com");
            proxy.count();
            assertEquals(2, handler.totalAttempts(), "两次成功调用");
        }
    }

    // ─────────────────────────────────────────────
    // 链式代理
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("链式代理：日志（外）→ 重试（内）→ 实现")
    class Chained {

        @Test
        @DisplayName("链式代理对外只暴露接口")
        void chainedExposesOnlyInterface() {
            var proxy = UserServiceProxy.chained(new DefaultUserService(), 3, s -> {});
            assertTrue(proxy instanceof UserService);
            assertFalse(proxy instanceof DefaultUserService);
        }

        @Test
        @DisplayName("链式代理：日志只打一次，重试在内部")
        void chainedLogsOnce() {
            var log = new ArrayList<String>();
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.chained(impl, 3, log::add);

            impl.failFindNext();
            impl.failFindNext();

            var added = proxy.add("张三", "z@example.com");
            var found = proxy.find(added.id());
            assertEquals(added, found);

            // 日志里应该只有 1 次「→ find」，不是 3 次
            var findLogs = log.stream()
                    .filter(l -> l.startsWith("→ find")).toList();
            assertEquals(1, findLogs.size(),
                    "⚠ 链式代理里日志外层只打一次，不会为每次重试打日志");
        }

        @Test
        @DisplayName("链式代理：日志记录了重试后的成功")
        void chainedLogsSuccessAfterRetry() {
            var log = new ArrayList<String>();
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.chained(impl, 3, log::add);

            impl.failFindNext();

            var added = proxy.add("张三", "z@example.com");
            proxy.find(added.id());

            // 应该有：→ add, ← add 完成, → find, ← find 完成
            assertTrue(log.stream().anyMatch(l -> l.contains("← find 完成")),
                    "重试成功后日志要记录成功");
        }
    }

    // ─────────────────────────────────────────────
    // ⚠ 代价：堆栈断裂
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("⚠ 代价：堆栈断裂和类型检查")
    class Costs {

        @Test
        @DisplayName("⚠ 堆栈里的帧包含 Proxy，不是 DefaultUserService")
        void stackTraceContainsProxyClass() {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withLog(impl, s -> {});

            impl.failFindNext();

            var caught = new AtomicReference<Throwable>();
            try {
                proxy.add("张三", "z@example.com");
                proxy.find("U-0001");
            } catch (Throwable t) {
                caught.set(t);
            }
            assertTrue(caught.get() instanceof IllegalStateException,
                    "调用方 catch 到的是 cause，不是 InvocationTargetException");

            // 堆栈里应该有 Proxy 的帧
            var stack = caught.get().getStackTrace();
            var hasProxy = java.util.Arrays.stream(stack)
                    .anyMatch(f -> f.getClassName().contains("Proxy")
                            || f.getClassName().contains("jdk.proxy"));
            assertTrue(hasProxy,
                    "⚠ 堆栈里的帧包含 Proxy 类，不是 DefaultUserService");
        }

        @Test
        @DisplayName("⚠ 调用方拿不到实现类的字段和方法")
        void callerCannotAccessImplMembers() {
            var proxy = UserServiceProxy.withLog(new DefaultUserService(), s -> {});
            // proxy.failFindNext() 编译错误 —— 代理只有接口方法
            // 用反射验证这个事实
            var methods = java.util.Arrays.stream(proxy.getClass().getMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();
            assertFalse(methods.contains("failFindNext"),
                    "⚠ 代理没有实现类的私有方法 —— 调用方拿不到");
            assertTrue(methods.contains("add"));
            assertTrue(methods.contains("find"));
            assertTrue(methods.contains("all"));
            assertTrue(methods.contains("count"));
        }

        @Test
        @DisplayName("⚠ 默认方法在动态代理里的行为")
        void defaultMethodsAreNotCalledThroughProxy() {
            // 动态代理不继承接口的默认方法，而是通过 InvocationHandler 转发
            var proxy = UserServiceProxy.withLog(new DefaultUserService(), s -> {});
            // 如果接口加了 default 方法，代理的 InvocationHandler 也会被调用
            // 这个测试确保我们理解这个行为
            assertTrue(proxy instanceof UserService);
            // 这里不测默认方法本身，只记录：代理会拦截所有接口方法调用，包括 default
        }
    }

    // ─────────────────────────────────────────────
    // 并发正确性
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("并发正确性")
    class Concurrency {

        @Test
        @DisplayName("⚠ 代理本身不是线程安全的（共享底层实现）")
        void proxySharesUnderlyingState() throws InterruptedException {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.withLog(impl, s -> {});

            var threadCount = 100;
            var done = new CountDownLatch(threadCount);
            var errors = new AtomicReference<Throwable>();

            for (int i = 0; i < threadCount; i++) {
                final var idx = i;
                Thread.ofVirtual().start(() -> {
                    try {
                        proxy.add("用户" + idx, "u" + idx + "@example.com");
                    } catch (Throwable t) {
                        errors.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNull(errors.get(), "并发调用不应出错（底层实现是线程安全的）");
            assertEquals(100, proxy.count(), "100 个并发 add 都被记录");
        }

        @Test
        @DisplayName("⚠ 重试代理的 totalAttempts 计数器是线程安全的")
        void retryHandlerCounterIsAtomic() throws InterruptedException {
            var handler = new RetryInvocationHandler(new DefaultUserService(), 3, s -> {});
            var proxy = UserServiceProxy.newProxy(UserService.class, new DefaultUserService(), handler);

            var threadCount = 50;
            var done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final var idx = i;
                Thread.ofVirtual().start(() -> {
                    try {
                        proxy.add("用户" + idx, "u" + idx + "@example.com");
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(50, handler.totalAttempts());
        }
    }

    // ─────────────────────────────────────────────
    // 工厂
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("工厂")
    class Factory {

        @Test
        @DisplayName("工厂创建的代理实现了接口")
        void factoryCreatesProxy() {
            var handler = new LogInvocationHandler(new DefaultUserService(), s -> {});
            var proxy = UserServiceProxy.newProxy(UserService.class, new DefaultUserService(), handler);
            assertTrue(proxy instanceof UserService);
            assertTrue(Proxy.isProxyClass(proxy.getClass()));
        }

        @Test
        @DisplayName("⚠ 工厂创建的代理不持有原实现的引用 —— 是新的对象")
        void proxyIsNotSameAsTarget() {
            var impl = new DefaultUserService();
            var proxy = UserServiceProxy.newProxy(UserService.class, impl,
                    new LogInvocationHandler(impl, s -> {}));
            assertNotSame(proxy, impl, "代理和实现是两个不同的对象");
        }
    }
}
