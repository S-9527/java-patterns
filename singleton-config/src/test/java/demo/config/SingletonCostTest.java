package demo.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("单例 —— 三种写法的代价")
class SingletonCostTest {

    @BeforeEach
    void setUp() {
        StaticConfig.INSTANCE.reset();
        DclConfig.resetForTest();
    }

    // ─────────────────────────────────────────────
    // 代价 1：隐藏依赖
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("代价 1：隐藏依赖 —— 构造器签名里没有配置")
    class HiddenDependency {

        @Test
        @DisplayName("⚠ GlobalSingletonService 的构造器不提到配置，但运行时摸全局")
        void constructorHidesConfigDependency() {
            var ctorParams = GlobalSingletonService.class.getDeclaredConstructors()[0]
                    .getParameters();
            assertEquals(1, ctorParams.length);
            assertEquals(String.class, ctorParams[0].getType(),
                    "构造器只有一个 String 参数，看不出依赖配置");

            // 但方法调用时确实读了全局配置
            var svc = new GlobalSingletonService("A");
            assertEquals(3000, svc.timeoutMs(), "实际是从全局配置读的");
        }

        @Test
        @DisplayName("⚠ InjectedService 的构造器签名显式声明 ConfigSource")
        void injectionMakesDependencyVisible() {
            var ctorParams = InjectedService.class.getRecordComponents();
            assertEquals(2, ctorParams.length);
            assertEquals(ConfigSource.class, ctorParams[1].getType(),
                    "依赖在签名里，一眼可见");
        }

        @Test
        @DisplayName("⚠ 全局配置改一次，所有服务跟着变")
        void globalConfigAffectsAllServices() {
            var svc1 = new GlobalSingletonService("A");
            var svc2 = new GlobalSingletonService("B");

            assertEquals(3000, svc1.timeoutMs());
            assertEquals(3000, svc2.timeoutMs());

            StaticConfig.INSTANCE.set(new ConfigKey("timeout.ms"),
                    new ConfigValue("9000", "test"));

            // 两个服务没有做任何改动，但都变了
            assertEquals(9000, svc1.timeoutMs(), "服务 A 被动改超时");
            assertEquals(9000, svc2.timeoutMs(), "服务 B 被动改超时");
        }

        @Test
        @DisplayName("✅ 注入式的服务只受影响于它自己的配置实例")
        void injectedServiceIsolated() {
            var a = new TestConfig().put("timeout.ms", "100");
            var b = new TestConfig().put("timeout.ms", "9999");

            var svcA = new InjectedService("A", a);
            var svcB = new InjectedService("B", b);

            a.set(new ConfigKey("timeout.ms"), new ConfigValue("200", "test"));
            assertEquals(200, svcA.timeoutMs(), "A 受 A 的配置影响");
            assertEquals(9999, svcB.timeoutMs(), "B 不受 A 的配置影响");
        }
    }

    // ─────────────────────────────────────────────
    // 代价 2：难测试
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("代价 2：难测试 —— 测试之间共享状态")
    class HardToTest {

        @Test
        @DisplayName("⚠ 一个测试改了全局配置，另一个测试读到残留值")
        void leakageBetweenTests() {
            // 模拟「测试 1」
            StaticConfig.INSTANCE.set(new ConfigKey("timeout.ms"),
                    new ConfigValue("30000", "test-1"));
            assertEquals(30000, new GlobalSingletonService("A").timeoutMs());

            // 模拟「测试 2」—— 它没有重置全局状态
            var svc = new GlobalSingletonService("B");
            assertEquals(30000, svc.timeoutMs(),
                    "⚠ 全局状态的残留：测试 2 拿到测试 1 设的值");
        }

        @Test
        @DisplayName("✅ @BeforeEach reset() 能救，但每个测试都得记得加")
        void resetSavesIt() {
            StaticConfig.INSTANCE.set(new ConfigKey("timeout.ms"),
                    new ConfigValue("30000", "test-1"));

            // 模拟 @BeforeEach
            StaticConfig.INSTANCE.reset();

            assertEquals(3000, new GlobalSingletonService("B").timeoutMs(),
                    "reset 之后拿回默认值");
        }

        @Test
        @DisplayName("⚠ 枚举单例没有公开的构造器 —— 测试没法用别的实例")
        void enumSingletonHasNoConstructor() {
            // 枚举的构造器是私有的，反射也拿不到
            var ctors = StaticConfig.class.getDeclaredConstructors();
            assertEquals(1, ctors.length);
            assertFalse(java.lang.reflect.Modifier.isPublic(ctors[0].getModifiers()),
                    "枚举构造器不公开，无法从外部 new 出第二个实例");
        }
    }

    // ─────────────────────────────────────────────
    // 代价 3：无法注入
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("代价 3：无法注入 —— 参数化测试做不了")
    class CannotInject {

        @Test
        @DisplayName("⚠ 单例下想测三种超时值只能串行改全局配置")
        void parametrizedTestWithSingleton() {
            for (var ms : List.of("100", "1000", "10000")) {
                StaticConfig.INSTANCE.set(new ConfigKey("timeout.ms"),
                        new ConfigValue(ms, "test"));
                assertEquals(Integer.parseInt(ms),
                        new GlobalSingletonService("A").timeoutMs());
            }
            // 每一步都改了全局，测试结束还得 reset —— 麻烦但能做
            StaticConfig.INSTANCE.reset();
        }

        @Test
        @DisplayName("✅ 注入式：三个服务实例各用一份配置，互不干扰")
        void parametrizedTestWithInjection() {
            for (var ms : List.of("100", "1000", "10000")) {
                var svc = new InjectedService("A",
                        new TestConfig().put("timeout.ms", ms));
                assertEquals(Integer.parseInt(ms), svc.timeoutMs());
            }
        }

        @Test
        @DisplayName("✅ 注入式可以并发跑：每个虚拟线程拿自己的配置")
        void concurrentInjectionIsSafe() throws InterruptedException {
            var configs = List.of(
                    new TestConfig().put("timeout.ms", "1"),
                    new TestConfig().put("timeout.ms", "2"),
                    new TestConfig().put("timeout.ms", "3")
            );

            var errors = new AtomicReference<Throwable>();
            var done = new CountDownLatch(configs.size());

            for (int i = 0; i < configs.size(); i++) {
                final var cfg = configs.get(i);
                final var expected = Integer.parseInt(
                        cfg.get(new ConfigKey("timeout.ms")).orElseThrow().raw());
                Thread.ofVirtual().start(() -> {
                    try {
                        var svc = new InjectedService("A", cfg);
                        assertEquals(expected, svc.timeoutMs());
                    } catch (Throwable t) {
                        errors.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(null, errors.get(), "并发注入式测试不应出错");
        }
    }

    // ─────────────────────────────────────────────
    // 并发正确性：DCL 只初始化一次
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("并发正确性：200 个虚拟线程抢初始化")
    class ConcurrentInit {

        @Test
        @DisplayName("⚠ DCL：构造器只调用 1 次 —— volatile 是全部关键")
        void dclConstructsOnce() throws InterruptedException {
            var threadCount = 200;
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threadCount);
            var error = new AtomicReference<Throwable>();

            for (int i = 0; i < threadCount; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        DclConfig.getInstance();
                    } catch (Throwable t) {
                        error.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "所有线程应完成");
            assertEquals(null, error.get(), "并发初始化不应出错");

            assertEquals(1, DclConfig.getInstance().initCount(),
                    "⚠ 200 个虚拟线程抢初始化，构造器只调用 1 次");
        }

        @Test
        @DisplayName("⚠ 枚举单例也是只初始化一次 —— 但这是 JVM 保证的")
        void enumSingletonIsInherentlyThreadSafe() {
            // 枚举的常量在类加载时一次性初始化，JVM 保证线程安全
            var a = StaticConfig.INSTANCE;
            var b = StaticConfig.INSTANCE;
            assertSame(a, b, "枚举单例天然只有一份");
        }

        @Test
        @DisplayName("⚠ Holder 也是只初始化一次 —— 但也是 JVM 保证的")
        void holderIsInherentlyThreadSafe() {
            var a = StaticHolderConfig.getInstance();
            var b = StaticHolderConfig.getInstance();
            assertSame(a, b, "Holder 的静态字段在类加载时初始化");
        }

        @Test
        @DisplayName("⚠ 三个单例的 getInstance 并发调用都稳定")
        void allSingletonsAreStableUnderConcurrentAccess() throws InterruptedException {
            var threadCount = 100;
            var done = new CountDownLatch(threadCount);
            var errors = new AtomicReference<Throwable>();

            for (int i = 0; i < threadCount; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        var a = StaticConfig.INSTANCE;
                        var b = StaticHolderConfig.getInstance();
                        var c = DclConfig.getInstance();
                        assertNotNull(a);
                        assertNotNull(b);
                        assertNotNull(c);
                    } catch (Throwable t) {
                        errors.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(null, errors.get(), "三种单例的并发访问都不应出错");
        }
    }

    // ─────────────────────────────────────────────
    // 配置键/值
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("配置键/值")
    class KeysAndValues {

        @Test
        @DisplayName("ConfigKey 校验")
        void keyValidation() {
            assertThrows(IllegalArgumentException.class, () -> new ConfigKey(""));
            assertThrows(IllegalArgumentException.class, () -> new ConfigKey("   "));
            assertThrows(IllegalArgumentException.class, () -> new ConfigKey(null));
            assertEquals(new ConfigKey("app.name"), new ConfigKey("app.name"));
        }

        @Test
        @DisplayName("ConfigValue 展示来源")
        void valueShowsSource() {
            assertEquals("3000 (from default)",
                    new ConfigValue("3000", "default").toString());
        }

        @Test
        @DisplayName("TestConfig 的 put 链式调用")
        void testConfigChaining() {
            var cfg = new TestConfig().put("a", "1").put("b", "2");
            assertEquals(Set.of(new ConfigKey("a"), new ConfigKey("b")), cfg.keys());
            assertTrue(cfg.get(new ConfigKey("a")).isPresent());
            assertTrue(cfg.get(new ConfigKey("missing")).isEmpty());
        }

        @Test
        @DisplayName("⚠ Map.of 不允许 null 键 —— ConfigKey 构造器抛了")
        void mapOfForbidsNullKey() {
            // 这条其实是被 ConfigKey 构造器挡住，但如果哪天有人直接传 null
            // 给 Map.of，也会 NPE。留在这里提醒。
            assertThrows(IllegalArgumentException.class, () -> new ConfigKey(null));
        }

        @Test
        @DisplayName("注入式服务缺配置时用默认值")
        void injectedServiceFallsBackToDefault() {
            var svc = new InjectedService("A", new TestConfig());
            assertEquals(1000, svc.timeoutMs(), "缺配置时 fallback 到 1000ms");
        }
    }

    // ─────────────────────────────────────────────
    // 单例本身的行为
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("单例本身的行为")
    class SingletonBehavior {

        @Test
        @DisplayName("DclConfig 用 null 删除键")
        void dclConfigRemovesKey() {
            var cfg = DclConfig.getInstance();
            cfg.put(new ConfigKey("a"), "1");
            assertTrue(cfg.get(new ConfigKey("a")).isPresent());

            cfg.put(new ConfigKey("a"), null);
            assertFalse(cfg.get(new ConfigKey("a")).isPresent(),
                    "传 null 删除键 —— DCL 版本约定的语义");
        }

        @Test
        @DisplayName("DclConfig 重置后重新初始化")
        void dclConfigReinitializesAfterReset() {
            var first = DclConfig.getInstance();
            assertEquals(1, first.initCount());

            DclConfig.resetForTest();
            var second = DclConfig.getInstance();
            assertEquals(1, second.initCount(), "新实例的计数器从 1 开始");
            assertNotEquals(first, second, "是两个不同的对象");
        }

        @Test
        @DisplayName("⚠ 枚举单例的字段是可变状态 —— 不是不可变单例")
        void enumSingletonHoldsMutableState() {
            StaticConfig.INSTANCE.set(new ConfigKey("app.name"),
                    new ConfigValue("changed", "test"));
            assertEquals("changed",
                    StaticConfig.INSTANCE.get(new ConfigKey("app.name")).orElseThrow().raw(),
                    "枚举单例的字段是可以被改的 —— 这不是不可变单例");
        }
    }
}
