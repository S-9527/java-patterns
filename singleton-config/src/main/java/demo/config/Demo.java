package demo.config;

/**
 * 单例：三种写法 + 三个代价。
 *
 * <p>本模块是<b>反面教材</b>，重点不是"怎么写单例"，而是"单例让你付出了什么"。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        heading("三种写法都在，但问题是同一个");

        out("  1. 枚举     public enum Config { INSTANCE; ... }");
        out("     读者看到 INSTANCE，不需要知道它是单例");
        out("     但不能用构造器传参，初始化时机固定");
        out();
        out("  2. 静态持有者  private static final class Holder { static final X I = ...; }");
        out("     延迟到首次访问加载，不需要 volatile");
        out("     （泛型场景会退化，见 StaticHolderConfig 的注释）");
        out();
        out("  3. 双重检查锁  volatile + 两次 null 检查");
        out("     volatile 是 DCL 成立的全部关键，位置放错就退化");
        out();
        out("  ⚠ 三者都正确实现了「只初始化一次」。");
        out("    但下面三个问题一个都没解决 —— 那是单例本身的，不是写法的。");

        heading("代价 1：隐藏依赖");
        costHiddenDependency();

        heading("代价 2：难测试");
        costHardToTest();

        heading("代价 3：无法注入");
        costCannotInject();

        heading("DCL 的并发正确性：200 个虚拟线程只建 1 次");
        costConcurrentInit();

        heading("什么时候单例其实是合理的");
        whenSingletonIsOk();

        System.out.println();
    }

    private static void costHiddenDependency() {
        var hidden = new GlobalSingletonService("服务A");
        var explicit = new InjectedService("服务A",
                new TestConfig().put("timeout.ms", "5000"));

        out("  看看这两个构造调用：");
        out();
        out("      new GlobalSingletonService(\"服务A\")   ← 看起来什么都不依赖");
        out("      new InjectedService(\"服务A\", new TestConfig().put(\"timeout.ms\", \"5000\"))");
        out();
        out("  实际上第一个也会摸全局配置。");
        out();
        out("    %s".formatted(hidden.describe()));
        out("    %s".formatted(explicit.describe()));
        out();
        out("  第二个一眼看出配置从哪来，改超时只影响自己。");
        out("  第一个的配置散落在全局，改了会影响每一个调用点。");
    }

    private static void costHardToTest() {
        out("  测试之间共享同一份状态。演示一下：");
        out();
        out("    测试 1 把超时改成 30000，断言 timeoutMs() == 30000");
        StaticConfig.INSTANCE.set(new ConfigKey("timeout.ms"),
                new ConfigValue("30000", "test"));
        out("    测试 2（没设超时）读同一个键，拿到 %d ← 测试 1 的残留"
                .formatted(new GlobalSingletonService("服务A").timeoutMs()));
        StaticConfig.INSTANCE.reset();

        out();
        out("  所以每个碰全局配置的测试都得加 @BeforeEach reset()。");
        out("  真实项目里这钩子经常没人记得加，于是就出现了「单独跑绿、一起跑红」的测试。");
        out();
        out("  而且测试的执行顺序不可控 —— 一旦有人动了 Maven 的 surefire 配置，");
        out("  或者将来改用并行测试，这些共享状态会开始互相打架。");
    }

    private static void costCannotInject() {
        out("  想测「超时 100 / 1000 / 10000」三种情况？单例做不到，");
        out("  因为全局只有一份配置。真实项目里这种测试通常干脆就没写。");
        out();
        out();
        out("  注入式的写法：");
        out("      for (var ms : List.of(\"100\", \"1000\", \"10000\")) {");
        out("          var svc = new InjectedService(\"服务A\", new TestConfig().put(\"timeout.ms\", ms));");
        out("          assertEquals(ms, String.valueOf(svc.timeoutMs()));");
        out("      }");
        out();
        for (var ms : java.util.List.of("100", "1000", "10000")) {
            var svc = new InjectedService("服务A",
                    new TestConfig().put("timeout.ms", ms));
            out("      ms=%-6s → timeoutMs() = %d".formatted(ms, svc.timeoutMs()));
        }
        out();
        out("  三种配置各测各的，互不影响 —— 单例给不了这个。");
    }

    private static void costConcurrentInit() {
        var threadCount = 200;
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threadCount);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        DclConfig.resetForTest();

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
        try {
            if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                out("  ✗ 线程未在 10 秒内完成 —— 这本身就是个问题");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        out("  %d 个虚拟线程同时抢初始化".formatted(threadCount));
        out("  构造器被调用 %d 次".formatted(DclConfig.getInstance().initCount()));
        out();
        out("  ✅ 断言 1 次。volatile + 两次 null 检查确实挡住了并发。");
        out();
        out("  ⚠ 但请注意：这个测试证明的是「能正确初始化」，");
        out("    不是「该用单例」。能写对和该不该用是两个问题。");
    }

    private static void whenSingletonIsOk() {
        out("  1. 真的只有一个实例 —— 硬件句柄、进程级连接池");
        out("     而不是「我希望只有一个」");
        out();
        out("  2. 无状态，或者只读后就不再变");
        out("     （配置加载完就冻结，运行时不再改）");
        out();
        out("  3. 只有一处会用到，不参与循环依赖");
        out("");
        out("  不满足这三条，纯静态单例就不合适。");
        out("  运行时配置（随时会改）特别不适合，测试时已经演示了代价。");
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