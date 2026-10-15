# singleton-config — 单例（反面教材）

## 场景

全局配置。三种写法都正确实现了"只初始化一次"，但它们没有解决单例本身的任何问题。

## 核心结论：写法进步了，问题原封不动

三种写法（枚举 / 静态持有者 / 双重检查锁）在 Java 里都能写出**线程安全且只初始化一次**的版本。
但这个模块想说的不是这个 —— **能写对和该不该用是两个问题**。

下面三个代价是单例本身的，不是写法的。换写法一个都解决不了：

| 代价 | 表现 |
|---|---|
| 隐藏依赖 | 构造器签名里没有配置，但方法里摸全局 |
| 难测试 | 测试之间共享状态，改一个影响另一个 |
| 无法注入 | 全局只有一份，参数化测试做不了 |

## 三种写法

```java
// 1. 枚举：读者看到 INSTANCE，不需要知道它是单例
public enum StaticConfig { INSTANCE; ... }

// 2. 静态持有者：延迟到首次访问加载，不需要 volatile
private static final class Holder {
    static final StaticHolderConfig INSTANCE = new StaticHolderConfig();
}

// 3. 双重检查锁：volatile 是全部关键
private static volatile DclConfig instance;
public static DclConfig getInstance() {
    if (instance == null) {
        synchronized (DclConfig.class) {
            if (instance == null) instance = new DclConfig();
        }
    }
    return instance;
}
```

`volatile` 的位置是关键。DCL 的语义依赖它 —— 没有 `volatile`，写线程可能暴露一个"半构造"的实例。
测试 `dclConstructsOnce` 用 200 个虚拟线程抢初始化，断言构造器只调用 1 次。

## 代价 1：隐藏依赖

`GlobalSingletonService` 的构造器只有一个 `String` 参数，看不出依赖配置。
但只要它跑起来，它就摸 `StaticConfig.INSTANCE`。

改成注入式的 `InjectedService` 之后，依赖在签名里，一眼可见：

```java
new InjectedService("服务A", new TestConfig().put("timeout.ms", "5000"))
```

测试 `globalConfigAffectsAllServices` 断言了这个代价 —— 改一次全局配置，两个服务都被动改超时。

## 代价 2：难测试

测试 `leakageBetweenTests` 模拟了两个测试共享全局状态的场景：
测试 1 设了超时 30000，测试 2 没重置，就拿到了 30000 而不是默认值 3000。

**"单独跑绿、一起跑红"** 的测试就是这么来的。每个碰全局配置的测试都得加 `@BeforeEach reset()`，
真实项目里这钩子经常没人记得加。

`enumSingletonHasNoConstructor` 断言了枚举构造器不公开 —— 测试没法 new 出第二个实例。

## 代价 3：无法注入

`parametrizedTestWithSingleton` 演示了用单例做参数化测试只能串行改全局配置。
`parametrizedTestWithInjection` 用三个注入实例各跑一组，互不干扰。

`concurrentInjectionIsSafe` 进一步断言：注入式可以并发跑 —— 每个虚拟线程拿自己的配置。

## DCL 并发正确性

`dclConstructsOnce` —— 200 个虚拟线程同时调用 `DclConfig.getInstance()`，
断言构造器只调用 1 次。这是本项目**第一个真正的并发正确性测试**。

```java
@Test
void dclConstructsOnce() throws InterruptedException {
    var threadCount = 200;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threadCount);
    // ... 200 个虚拟线程同时 await 然后 getInstance
    assertEquals(1, DclConfig.getInstance().initCount(),
            "⚠ 200 个虚拟线程抢初始化，构造器只调用 1 次");
}
```

但这个测试只证明"能正确初始化"。`StaticConfig` 的 `INSTANCE` 字段同样可以被并发修改 ——
见 `enumSingletonHoldsMutableState` —— 因为它是可变状态，不是不可变单例。

## 什么时候单例其实是合理的

1. 真的只有一个实例 —— 硬件句柄、进程级连接池，而不是"我希望只有一个"
2. 无状态，或者只读后就不再变（配置加载完就冻结）
3. 只有一处会用到，不参与循环依赖

不满足这三条，纯静态单例就不合适。

## 类清单

| 类 | 角色 |
|---|---|
| `StaticConfig` | 枚举单例，反面教材的主角 |
| `StaticHolderConfig` | Holder 写法，对照 |
| `DclConfig` | 双重检查锁，带 `initCount` 计数器供测试断言 |
| `ConfigSource` | 接口，注入式那条路的入口 |
| `ConfigKey` / `ConfigValue` | 键值对，record |
| `GlobalSingletonService` | 隐藏依赖的角色 |
| `InjectedService` | 注入式对照，record |
| `TestConfig` | 测试用内存配置 |

## 踩到的坑

**1. 两个 public class 挤在一个文件里。**
`ConfigKeys.java` 里同时放了 `ConfigKey` 和 `ConfigValue`，编译报
"class ConfigKey is public, should be declared in a file named ConfigKey.java"。
拆成两个文件。

**2. `StaticHolderConfig` 最初做成泛型 `StaticHolderConfig<T>`，然后 `getInstance<T>` 返回 `(StaticHolderConfig<T>) Holder.INSTANCE`。**
问题是：Holder 的静态字段只能是具体类型，所以实例化时退化成 String 版本。
而且构造器是私有的，测试里没法用 `new` 做对照。

改回非泛型 `StaticHolderConfig` 更诚实 —— **教科书上那种漂亮的泛型 Holder 写法在实际项目里往往不成立**，
留一个非泛型版本更能反映真实情况。

**3. `Consumer.accept` / lambda 里引用 `int i` 循环变量 —— 不是 effectively final。**
在 `concurrentInjectionIsSafe` 里第一次写的时候忘了，把 `configs.get(i)` 提到 lambda 外面：

```java
for (int i = 0; i < configs.size(); i++) {
    final var cfg = configs.get(i);
    final var expected = Integer.parseInt(cfg.get(...).orElseThrow().raw());
    Thread.ofVirtual().start(() -> { /* 捕获 cfg 和 expected */ });
}
```

## 怎么跑

```bash
mvn -q -pl singleton-config compile exec:java -Dexec.mainClass=demo.config.Demo
```

## 测试点（22 条）

- 隐藏依赖：构造器签名不提到配置，但运行时摸全局
- 全局配置改一次，所有服务跟着变
- 注入式的服务只受影响于它自己的配置实例
- 测试之间共享状态（`leakageBetweenTests`）
- `@BeforeEach reset()` 能救，但每个测试都得记得加
- 枚举构造器不公开，测试没法用别的实例
- 单例下参数化测试只能串行改全局
- 注入式：三个服务实例各用一份配置，互不干扰
- 注入式可以并发跑（3 个虚拟线程）
- **200 个虚拟线程抢 DCL 初始化，构造器只调用 1 次**
- 枚举单例的字段是可变的（不是不可变单例）
- 配置键校验、ConfigValue 展示来源、TestConfig 链式调用、缺配置 fallback 默认值

## 延伸

- GoF 原文第 3 章 Singleton
- `catalog-flyweight` 的 `ProductMetaFactory` 也是一个单例（Holder 写法），但那里的目的是共享而不是全局
- 延伸阅读：为什么"单例是反模式"是一个过度简化的说法 —— 硬件句柄、连接池、日志器都是合理的单例场景
