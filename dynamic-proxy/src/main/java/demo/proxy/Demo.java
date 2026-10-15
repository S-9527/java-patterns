package demo.proxy;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态代理：加横切关注点，代价是失去编译期检查和堆栈连续性。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var log = new ArrayList<String>();

        heading("日志代理：给每个方法加一行日志");
        var impl = new DefaultUserService();
        var proxy = UserServiceProxy.withLog(impl, log::add);
        out("  proxy.getClass().getName() = %s".formatted(proxy.getClass().getName()));
        out("  proxy 是 UserService？ %b".formatted(proxy instanceof UserService));
        out("  proxy 是 DefaultUserService？ %b  ← 不是，只能看到接口"
                .formatted(proxy instanceof DefaultUserService));
        out();
        proxy.add("张三", "zhangsan@example.com");
        proxy.find("U-0001");
        log.forEach(l -> out("    " + l));
        out("  注意：每次调用都被记录，实现类不用改一行代码。");

        heading("链式代理：日志（外）→ 重试（内）→ 实现");
        var log2 = new ArrayList<String>();
        var impl2 = new DefaultUserService();
        var proxy2 = UserServiceProxy.chained(impl2, 3, log2::add);
        impl2.failFindNext();   // 第一次 find 会抛异常
        impl2.failFindNext();   // 第二次也抛
        proxy2.add("李四", "lisi@example.com");
        proxy2.find("U-0001");
        log2.forEach(l -> out("    " + l));
        out();
        out("  重试代理在 3 次内成功了 —— 前两次抛异常，第三次拿到真值。");
        out("  日志只打了 1 行「→ find」，没有为每次重试打日志 —— 噪音小。");

        heading("⚠ 堆栈断裂：调用方看到的是 Proxy，不是实现");
        var log3 = new ArrayList<String>();
        var impl3 = new DefaultUserService();
        var proxy3 = UserServiceProxy.withLog(impl3, log3::add);
        impl3.failFindNext();   // 让 find 抛异常
        try {
            proxy3.add("王五", "wangwu@example.com");
            proxy3.find("U-0001");
        } catch (Exception e) {
            out("  调用方 catch 到：%s".formatted(e.getMessage()));
            out("  异常堆栈（前 5 帧）：");
            var frames = e.getStackTrace();
            for (int i = 0; i < Math.min(5, frames.length); i++) {
                out("    [%d] %s".formatted(i, frames[i]));
            }
            out();
            var hasProxyFrame = java.util.Arrays.stream(frames)
                    .anyMatch(f -> f.getClassName().contains("Proxy")
                            || f.getClassName().contains("jdk.proxy"));
            out("  堆栈里有 Proxy 帧？ %b".formatted(hasProxyFrame));
            out("  堆栈里有 DefaultUserService 帧？ %b".formatted(
                    java.util.Arrays.stream(frames)
                            .anyMatch(f -> f.getClassName().contains("DefaultUserService"))));
            out();
            out("  ⚠ 堆栈里的 Proxy 类名是动态生成的（形如 $Proxy42），");
            out("    调试器无法把它关联到 DefaultUserService 的源码行。");
            out("    这是动态代理和手写装饰器（如 money-decorator 场景）的关键区别。");
        }

        heading("⚠ 编译期检查只对接口有效");
        out("  调用方只能通过接口调用方法 —— 调一个接口里不存在的方法，编译期就报错。");
        out();
        out("  但调用方也拿不到实现类的任何成员：");
        out("    - 私有方法（如 failFindNext）—— 代理类里没有");
        out("    - 实现类的字段 —— 代理类里没有");
        out();
        out("  具体断言见 ProxyTest.callerCannotAccessImplMembers。");

        heading("⚠ 性能：反射 vs invokedynamic");
        out("  Proxy.newProxyInstance 走反射，每次调用开销 ~100ns 量级。");
        out("  在热点路径上这个开销会累加。");
        out();
        out("  更快的方式：LambdaMetafactory + invokedynamic，但要求");
        out("  目标接口是 @FunctionalInterface（只有一个抽象方法）。");
        out("  本项目 zero 第三方依赖，不展开演示，但真实项目里这是标准选择。");

        heading("什么时候动态代理是合理的");
        out("  1. 横切关注点：日志、事务、权限、指标 —— 不想污染业务代码");
        out("  2. 接口是稳定的，实现会变 —— 代理层不受实现变更影响");
        out("  3. 调用方不关心实现细节，只关心接口");
        out();
        out("  不合理的场景：");
        out("  - 单接口的简单包装（手写装饰器更清晰，如 money-decorator）");
        out("  - 需要反射字段访问的代理（动态代理不支持，得用 CGLIB）");
        out("  - 热点路径（用 invokedynamic 或手写字节码）");

        System.out.println();
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
