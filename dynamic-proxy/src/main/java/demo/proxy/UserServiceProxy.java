package demo.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * 代理工厂 —— 用 {@link Proxy#newProxyInstance} 创建代理。
 *
 * <p>这是 JDK 动态代理的<b>唯一</b>创建方式。它接受三样东西：
 * <ol>
 *   <li>类加载器</li>
 *   <li>接口数组（一个接口，或者多个）</li>
 *   <li>{@link InvocationHandler}（所有方法调用的入口）</li>
 * </ol>
 *
 * <p>返回的对象是 JVM 动态生成的一个类，它的名字形如
 * {@code $Proxy42}。看 {@link ProxyTest} 怎么暴露这个事实。
 *
 * <p><b>类型安全</b>：工厂方法显式接收接口类，避免泛型推断把 T 推断成
 * 实现类（代理不是实现类，那样会 ClassCastException）。
 */
public final class UserServiceProxy {

    private UserServiceProxy() {
    }

    /** 包装成日志代理。 */
    public static UserService withLog(DefaultUserService target, Consumer<String> sink) {
        return newProxy(UserService.class, target, new LogInvocationHandler(target, sink));
    }

    /** 包装成重试代理。 */
    public static UserService withRetry(DefaultUserService target, int maxAttempts,
                                        Consumer<String> sink) {
        return newProxy(UserService.class, target,
                new RetryInvocationHandler(target, maxAttempts, sink));
    }

    /**
     * 链式代理：日志（外层）→ 重试（内层）→ 真实实现。
     *
     * <p>顺序很重要：外层负责记录调用，内层负责重试。
     * 反过来（重试在外、日志在内）的话，每次重试都会被日志记录一次，噪音大。
     */
    public static UserService chained(DefaultUserService target, int maxAttempts,
                                       Consumer<String> sink) {
        var inner = newProxy(UserService.class, target,
                new RetryInvocationHandler(target, maxAttempts, sink));
        // 外层拿到的 target 是内层代理，不是原始实现
        return newProxy(UserService.class, inner, new LogInvocationHandler(inner, sink));
    }

    /**
     * 通用工厂 —— 显式接收接口类，返回类型的代理。
     *
     * <p>第一个参数是 {@code Class<T>}，不是 target。
     * 这样 {@code T} 被接口类确定，不会和实现类混淆。
     *
     * @param iface  代理要实现的接口
     * @param target 被代理的目标对象
     * @param handler 调用处理器
     */
    public static <T> T newProxy(Class<T> iface, Object target, InvocationHandler handler) {
        // ⚠ 类加载器：这里用接口的 loader。
        //   真实场景里如果用类加载器隔离（比如 OSGi），这里要传专门的 loader。
        return iface.cast(Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                handler));
    }
}
