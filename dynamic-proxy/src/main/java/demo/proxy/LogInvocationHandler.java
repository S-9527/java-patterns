package demo.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志代理 —— 动态代理的<b>教科书场景</b>：给一个接口方法加横切关注点。
 *
 * <p>它做的事很单纯：调用前打一行，调用后打一行。
 * 但代价是<b>堆栈断裂</b> —— 从调用方的堆栈里，你看不到真实的
 * {@link DefaultUserService}，只能看到一个 {@code Proxy$N} 包装层。
 *
 * <p>这个代价在 {@link ProxyDebugTest} 里有断言。
 */
public final class LogInvocationHandler implements InvocationHandler {

    private final Object target;
    private final java.util.function.Consumer<String> sink;
    private final AtomicLong calls = new AtomicLong();

    public LogInvocationHandler(Object target, java.util.function.Consumer<String> sink) {
        this.target = target;
        this.sink = sink;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        calls.incrementAndGet();
        sink.accept("→ %s%s".formatted(method.getName(), formatArgs(args)));
        try {
            var result = method.invoke(target, args);
            sink.accept("← %s 完成".formatted(method.getName()));
            return result;
        } catch (Throwable t) {
            sink.accept("← %s 抛异常：%s".formatted(method.getName(), t.getCause() != null
                    ? t.getCause().getMessage() : t.getMessage()));
            // ⚠ 注意：必须抛 cause，而不是 InvocationTargetException 本身。
            // 否则调用方拿到的是被包装的异常，catch 不到 IllegalStateException。
            throw unwrap(t);
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException e
                && e.getCause() != null) {
            return e.getCause();
        }
        return t;
    }

    private static String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return " ()";
        var sb = new StringBuilder(" (");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].toString());
        }
        return sb.append(")").toString();
    }

    /** 给测试用。 */
    public long callCount() {
        return calls.get();
    }
}
