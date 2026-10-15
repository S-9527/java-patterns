package demo.runner;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 全项目唯一的入口，按固定教学顺序调用各场景模块的 Demo。
 *
 * <p>这里用 {@link Class#forName} 按名字加载，而不是直接引用 14 个 Demo 类型 ——
 * 目的是让 runner 的编译结果和场景模块的进度解耦：某个场景还没写完，
 * 这个模块照样能构建、报错信息也直指缺哪个类。
 *
 * <p>用 {@code java -jar runner/target/java-patterns.jar <关键字>} 可以只跑名字包含关键字的场景。
 */
public final class RunAll {

    /** 一个场景的静态注册信息。顺序即教学顺序：结构型 → 创建型 → 行为型 → 高阶争议 → JDK 特性。 */
    private record Scenario(String module, String className) {}

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("money-decorator",   "demo.money.Demo"),
            new Scenario("order-builder",     "demo.builder.Demo"),
            new Scenario("order-state",       "demo.orderstate.Demo"),
            new Scenario("risk-chain",        "demo.risk.Demo"),
            new Scenario("pipeline",          "demo.pipeline.Demo"),
            new Scenario("domain-events",     "demo.events.Demo"),
            new Scenario("payment-gateway",   "demo.payment.Demo"),
            new Scenario("checkout-facade",   "demo.facade.Demo"),
            new Scenario("order-command",     "demo.command.Demo"),
            new Scenario("catalog-flyweight", "demo.flyweight.Demo"),
            new Scenario("singleton-config",  "demo.config.Demo"),
            new Scenario("dynamic-proxy",     "demo.proxy.Demo"),
            new Scenario("visitor-vs-switch", "demo.visitor.Demo"),
            new Scenario("scoped-values",     "demo.concurrency.Demo"));

    public static void main(String[] args) {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var keyword = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        var selected = SCENARIOS.stream()
                .filter(s -> s.module().contains(keyword))
                .toList();

        if (selected.isEmpty()) {
            out.println("没有匹配 '" + args[0] + "' 的场景。可用关键字：");
            SCENARIOS.forEach(s -> out.println("  " + s.module()));
            return;
        }

        var failed = new java.util.ArrayList<String>();
        for (var i = 0; i < selected.size(); i++) {
            var scenario = selected.get(i);
            out.printf("%n[%d/%d] %s%n%n", i + 1, selected.size(), scenario.module());
            try {
                run(scenario);
            } catch (ReflectiveOperationException e) {
                failed.add(scenario.module());
                out.println("!! 场景加载失败：" + rootCause(e));
            }
        }

        out.printf("%n%n%s%n", "─".repeat(60));
        if (failed.isEmpty()) {
            out.printf("全部 %d 个场景执行完毕。%n", selected.size());
        } else {
            out.printf("执行完毕，但 %d 个场景失败：%s%n", failed.size(), failed);
            System.exit(1);
        }
    }

    private static void run(Scenario scenario) throws ReflectiveOperationException {
        Class<?> demo = Class.forName(scenario.className());
        demo.getMethod("run").invoke(null);
    }

    /** InvocationTargetException 包着 Demo 内部的真实异常，这里剥掉包装。 */
    private static Throwable rootCause(Throwable t) {
        return (t instanceof InvocationTargetException ite && ite.getCause() != null)
                ? ite.getCause()
                : t;
    }
}