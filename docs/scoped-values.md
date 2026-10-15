# scoped-values — 虚拟线程 + ScopedValue vs ThreadLocal

## 场景

高并发下单，每条链路需要 traceId / customerId / tenant。

## 核心结论

**ScopedValue 不需要 `remove()`** —— 值绑定到作用域，作用域结束自动清理。

ThreadLocal 在虚拟线程上会内存泄漏（每个虚拟线程都占 ThreadLocalMap），
ScopedValue 不会。

## 三种方案对比

| | ThreadLocal | InheritableThreadLocal | ScopedValue |
|---|---|---|---|
| 需要 `remove()` | ✅ 必须 | ✅ 必须 | ❌ 不需要 |
| 自动作用域 | ❌ | ❌ | ✅ |
| 子线程继承 | ❌ | ✅（隐式） | ❌（显式） |
| 虚拟线程安全 | ❌ 内存泄漏 | ❌ 不兼容 | ✅ |
| 可变 | ✅ | ✅ | ❌ 不可变 |

## ScopedValue 的关键行为

### 1. 不可变

作用域内读取的值不可修改。但新 Carrier 可以 shadow：

```java
ScopedContext.run(ctx1, () -> {
    assertEquals(ctx1, ScopedContext.get());
    ScopedContext.run(ctx2, () -> {
        assertEquals(ctx2, ScopedContext.get()); // 内层读到 ctx2
    });
    assertEquals(ctx1, ScopedContext.get()); // 外层仍然 ctx1
});
```

### 2. 子线程不自动继承

⚠ **子虚拟线程不自动继承父线程的 ScopedValue。** 必须显式重新绑定：

```java
ScopedValue.where(CONTEXT, ctx).run(() -> {
    var captured = CONTEXT.get();
    Thread.ofVirtual().start(() -> {
        // 必须显式重新绑定
        ScopedValue.where(CONTEXT, captured).run(() -> {
            var current = CONTEXT.get(); // 现在有值
        });
    });
});
```

这是 ScopedValue 的设计哲学：**显式传播**，不像 InheritableThreadLocal 那样隐式继承。
好处是避免线程池复用时的交叉污染。

### 3. 作用域外访问抛异常

```java
ScopedContext.get(); // IllegalStateException，不是返回 null
```

### 4. 并发无交叉污染

100 个虚拟线程各用各的上下文，每个线程只看到自己的绑定：

```java
@Test
void hundredThreadsNoCrossContamination() throws InterruptedException {
    var contexts = generateContexts(100);
    var results = RequestProcessor.processConcurrently(contexts);
    assertEquals(100, results.size());
    // 每个结果应该是 "trace-XXX → trace-XXX"（输入 = 输出）
}
```

## ThreadLocal 在虚拟线程上的内存泄漏

虚拟线程是轻量级的（每个只占几 KB 栈），但 ThreadLocal 不是。
ThreadLocal 的值存在线程的 `ThreadLocalMap` 里，是强引用。

100 万个虚拟线程 + 每个都设了 ThreadLocal = 100 万个 `ThreadLocalMap`。
虚拟线程的生命周期短，但 ThreadLocal 不会自动清理。
如果忘了 `remove()`，这些 Map 会一直存在直到线程结束。

## ScopedContext API

```java
public final class ScopedContext {
    public static final ScopedValue<TraceContext> CONTEXT = ScopedValue.newInstance();

    public static void run(TraceContext ctx, Runnable action);
    public static <T> T call(TraceContext ctx, Callable<T> action) throws Exception;
    public static TraceContext get();
    public static boolean isBound();
    public static TraceContext getOrNull(); // 作用域外返回 null，不抛异常
}
```

## 踩到的坑

**1. 子虚拟线程不自动继承 ScopedValue。**
最初以为子虚拟线程在作用域内启动就能自动读到值。实际不行 ——
ScopedValue 的绑定是 per-thread 的，子线程必须显式重新绑定。

**2. `ScopedValue.Carrier.call()` 的签名不是 `Callable<T>`。**
`Carrier.call()` 接受 `ScopedValue.CallableOp`，不是 `java.util.concurrent.Callable`。
改用 `run()` + holder 实现 `call()`。

**3. 新 Carrier 可以 shadow，不抛异常。**
最初以为作用域内重新绑定会抛 `IllegalStateException`。
实际新 Carrier 创建新作用域，可以 shadow，外层不受影响。

## 类清单

| 类 | 角色 |
|---|---|
| `TraceContext` | 请求上下文 record |
| `ThreadLocalContext` | ThreadLocal 方案 |
| `InheritableContext` | InheritableThreadLocal 方案 |
| `ScopedContext` | ScopedValue 方案 |
| `RequestProcessor` | 请求处理器，演示三种方案 |

## 怎么跑

```bash
mvn -q -pl scoped-values compile exec:java -Dexec.mainClass=demo.concurrency.Demo
```

## 测试点（17 条）

- 作用域内可以读到值
- ⚠ 作用域外读不到 —— 自动清理
- ⚠ 作用域外 get() 抛 IllegalStateException
- ⚠ ScopedValue 不可变 —— 但新 Carrier 可以 shadow
- ScopedValue.call() 返回值
- ⚠ 子虚拟线程不自动继承 —— 必须显式重新绑定
- 子虚拟线程在作用域外启动，读不到值
- 100 个虚拟线程各用各的上下文，无交叉
- ⚠ ThreadLocal 并发下也可能无交叉（但需要手动 remove）
- ⚠ ThreadLocal 忘了 remove() 会泄漏
- ThreadLocal 需要手动 remove()
- ScopedValue 不需要 remove()
- ⚠ ThreadLocal 在虚拟线程上的内存开销
- getOrNull 在作用域外返回 null
- startVirtual 需要显式重新绑定
- TraceContext 是 record，不可变
- TraceContext toString 包含所有字段

## 什么时候不该用 ScopedValue

- **需要在作用域内修改上下文** —— ScopedValue 不可变
- **子线程传播频繁** —— 每次都要显式重新绑定，比 ThreadLocal 麻烦
- **需要序列化** —— ScopedValue 不支持
- **Java 版本 < 25** —— 需要 `--enable-preview`
