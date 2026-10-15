# dynamic-proxy — JDK 动态代理

## 场景

用户服务，用动态代理加日志和重试两个横切关注点。

## 核心机制

```java
UserService proxy = (UserService) Proxy.newProxyInstance(
        UserService.class.getClassLoader(),
        new Class<?>[]{UserService.class},
        handler);
```

`Proxy.newProxyInstance` 是 JDK 动态代理的**唯一**创建方式。返回的对象是 JVM 动态生成的一个类，
名字形如 `$Proxy42`。看 `Demo` 的输出：

```
proxy.getClass().getName() = jdk.proxy3.$Proxy26
proxy 是 UserService？ true
proxy 是 DefaultUserService？ false  ← 不是，只能看到接口
```

## 链式代理

```java
// 日志（外层）→ 重试（内层）→ 真实实现
var inner = newProxy(UserService.class, target, new RetryInvocationHandler(...));
return newProxy(UserService.class, inner, new LogInvocationHandler(inner, ...));
```

**顺序很重要**：外层负责记录调用，内层负责重试。反过来（重试在外、日志在内）的话，
每次重试都会被日志记录一次，噪音大。

`chainedLogsOnce` 测试断言了这一点 —— 重试 2 次成功后，日志里只有 1 行 `→ find`。

## ⚠ 代价 1：堆栈断裂

异常堆栈里的帧包含代理类，不是实现类：

```
[0] demo.proxy.DefaultUserService.find(DefaultUserService.java:34)
[1] java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(...)
[2] java.base/java.lang.reflect.Method.invoke(Method.java:565)
[3] demo.proxy.LogInvocationHandler.invoke(LogInvocationHandler.java:32)
[4] jdk.proxy3/jdk.proxy3.$Proxy26.find(Unknown Source)   ← 动态生成，没有源码行
```

`stackTraceContainsProxyClass` 测试断言了堆栈里有 Proxy 帧。

**调试器无法把 `$Proxy26` 关联到 `DefaultUserService` 的源码行。**
这是动态代理和手写装饰器（如 `money-decorator` 场景）的关键区别 —— 装饰器的堆栈是连续的。

## ⚠ 代价 2：只代理接口

动态代理只能代理**接口**，不能代理类。这是它和 CGLIB/ByteBuddy 的第一条区别。

`callerCannotAccessImplMembers` 测试断言了代理类没有实现类的私有方法（如 `failFindNext`）——
调用方拿不到实现类的任何成员。

## ⚠ 代价 3：JVM 缓存代理类

`differentHandlersShareProxyClass` 测试发现：**同一个接口的不同 handler 共享同一个代理类。**

```java
var logProxy = UserServiceProxy.withLog(impl, s -> {});
var retryProxy = UserServiceProxy.withRetry(impl, 3, s -> {});
assertEquals(logProxy.getClass(), retryProxy.getClass(),
        "⚠ JVM 按 (ClassLoader, 接口) 缓存代理类 —— 不同 handler 共享同一个代理类");
```

JVM 按 `(ClassLoader, interfaces[])` 缓存代理类。这意味着：
- 同一个接口的所有代理（不管 handler 是什么）都是同一个类
- 不同接口的代理是不同的类

## ⚠ 工厂方法的类型安全

最初的工厂方法用泛型推断：

```java
// ❌ 有 bug 的写法
public static <T> T newProxy(T target, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            target.getClass().getInterfaces(),
            handler);
}
```

当调用 `newProxy(new DefaultUserService(), handler)` 时，`T` 被推断为 `DefaultUserService`，
但代理只实现了 `UserService` —— 运行时 `ClassCastException`。

**修复**：显式接收接口类：

```java
// ✅ 正确的写法
public static <T> T newProxy(Class<T> iface, Object target, InvocationHandler handler) {
    return iface.cast(Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{iface},
            handler));
}
```

## ⚠ InvocationTargetException 的解包

`LogInvocationHandler` 必须解包 `InvocationTargetException`：

```java
private static Throwable unwrap(Throwable t) {
    if (t instanceof InvocationTargetException e && e.getCause() != null) {
        return e.getCause();
    }
    return t;
}
```

如果不解包，调用方 catch 到的是 `InvocationTargetException`，而不是原始的
`IllegalArgumentException` —— 类型信息丢失。

`proxyUnwrapsInvocationTargetException` 测试断言了这个行为。

## 什么时候动态代理是合理的

1. **横切关注点**：日志、事务、权限、指标 —— 不想污染业务代码
2. **接口稳定，实现会变** —— 代理层不受实现变更影响
3. **调用方不关心实现细节**，只关心接口

**不合理的场景**：
- 单接口的简单包装（手写装饰器更清晰，如 `money-decorator`）
- 需要反射字段访问的代理（动态代理不支持，得用 CGLIB）
- 热点路径（用 `invokedynamic` 或手写字节码）

## 类清单

| 类 | 角色 |
|---|---|
| `UserService` | 被代理的接口 |
| `DefaultUserService` | 实现，带 `failFindNext()` 测试钩子 |
| `LogInvocationHandler` | 日志代理，横切关注点 |
| `RetryInvocationHandler` | 重试代理，横切关注点 |
| `UserServiceProxy` | 工厂，显式接收接口类 |
| `User` | 用户记录 |

## 踩到的坑

**1. 工厂方法的泛型推断 bug。**
`newProxy(T target, ...)` 会把 T 推断为实现类（`DefaultUserService`），但代理只实现了接口。
运行时 `ClassCastException: jdk.proxy3.$Proxy24 cannot be cast to DefaultUserService`。
改成 `newProxy(Class<T> iface, ...)` 显式传接口类。

**2. JVM 缓存代理类。**
最初写了一个测试断言"不同 handler 创建不同的代理类"，但实际 JVM 按 `(ClassLoader, 接口)` 缓存代理类，
不同 handler 共享同一个代理类。改成断言它们**相同** —— 这也是个有价值的发现。

**3. `StackTraceElement` 没有 `getDeclaringClass()`。**
Demo 里最初用 `f.getDeclaringClass().getName().contains("Proxy")`，编译不过。
`StackTraceElement` 只有 `getClassName()`。

**4. `var lastFailure = null` 无法推断类型。**
`RetryInvocationHandler` 里最初用 `var lastFailure = null`，编译器无法推断 `var` 的类型。
改成显式 `Throwable lastFailure = null`。

**5. Demo 的堆栈输出用的是当前线程堆栈而不是异常堆栈。**
最初用 `Thread.currentThread().getStackTrace()`，看到的是 Demo 自己的帧，不是代理的帧。
改成 `e.getStackTrace()` 才能看到 `$Proxy26.find(Unknown Source)`。

## 怎么跑

```bash
mvn -q -pl dynamic-proxy compile exec:java -Dexec.mainClass=demo.proxy.Demo
```

## 测试点（24 条）

- 代理实现了接口（透明度）
- ⚠ 代理不是实现类（只能看到接口）
- 代理的方法调用结果和原始实现一致
- ⚠ 代理的 `getClass()` 是动态生成的 Proxy 类
- ⚠ 同一个接口的不同 handler 共享同一个代理类（JVM 缓存）
- 每次方法调用都被记录（日志代理）
- 异常也被记录，然后抛出
- ⚠ 代理抛出的是 cause，不是 InvocationTargetException
- 日志代理是线程安全的（callCount 计数器）
- 重试代理在 maxAttempts 内成功
- 重试代理超过 maxAttempts 后抛出最后一次的异常
- ⚠ 不可重试的异常不重试
- 重试次数 < 1 被拒绝
- 总尝试次数被正确累计
- 链式代理对外只暴露接口
- ⚠ 链式代理：日志只打一次，重试在内部
- 链式代理：日志记录了重试后的成功
- ⚠ 堆栈里的帧包含 Proxy，不是 DefaultUserService
- ⚠ 调用方拿不到实现类的字段和方法
- ⚠ 代理和实现是两个不同的对象
- 并发正确性：100 个虚拟线程并发调用代理
- ⚠ 重试代理的 totalAttempts 计数器是线程安全的

## 延伸

- GoF 原文第 8 章 Proxy
- `money-decorator` 场景 —— 手写装饰器，堆栈连续，是动态代理的对照
- 延伸阅读：`invokedynamic` + `LambdaMetafactory` —— 比反射快的代理方式，
  但要求接口是 `@FunctionalInterface`
