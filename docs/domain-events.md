# domain-events — 观察者

## 场景

订单支付成功后，四个下游都要做事：发短信、加积分、写审计日志、通知风控。

其中"发短信"依赖外部网关，**必然会失败**。

## 意图

定义一对多依赖。当一个对象状态改变时，所有依赖者自动收到通知。

## 类清单

| 类 | 角色 |
|---|---|
| `OrderEvent` | **sealed**，三种：OrderPlaced / PaymentCaptured / OrderCancelled |
| `OrderEventBus` | 事件总线，持有 `List<Listener>`，提供 lambda 注册重载 |
| `Listener` | 监听器接口，`name()` + `onEvent()`，**允许抛受检异常** |
| `DispatchResult` | **sealed**：`Ok` / `Failed`（带 `retryable`） |

## 核心决策：失败隔离 vs 失败传播

观察者模式最容易出的事故 —— 某个监听器挂了，把整个业务流程带崩。

```
✗ 发短信    短信网关超时 —— 重试无用，应告警
✓ 加积分    已收款 CNY 268.00（ALIPAY）
✓ 写审计日志  已收款 CNY 268.00（ALIPAY）
✓ 通知风控   已收款 CNY 268.00（ALIPAY）
```

**排在"发短信"后面的"写审计日志"照样执行了。** 这是隔离的关键价值。

不隔离的话，用户支付成功会因为短信失败而回滚 —— 不可接受。

但完全吞掉也不行。所以这里**捕获、隔离、然后如实上报**，让调用方决定要不要告警。

`propagateFailures(true)` 演示另一个极端，Demo 里能看到支付成功的订单被当成失败了：

```
✗ 监听器 发短信 失败
  支付成功的订单被当成失败了 —— 这就是为什么默认不这么干。
```

## 踩到的一个坑：`RuntimeException` 包装让重试判定失效

`isRetryable` 靠异常类型区分：

```java
if (e instanceof java.net.SocketTimeoutException) return true;
if (e instanceof java.io.IOException) return true;
if (e instanceof BlacklistedException) return false;
```

但 Demo 里最初写的是：

```java
throw new RuntimeException("短信网关超时");
```

结果被判成 **"重试无用，应告警"** —— 明显不对，超时当然该重试。

原因链：
1. `SocketTimeoutException` 和 `IOException` 是**受检异常**，不继承 `RuntimeException`
2. `Consumer.accept` 不声明受检异常，lambda 里没法直接 `throw` 它们
3. 于是被迫包成 `RuntimeException`，类型信息丢了，`instanceof` 判不出来

**修法**：`Listener.onEvent` 声明 `throws Exception`，配套一个 `ThrowingConsumer`：

```java
void onEvent(OrderEvent event) throws Exception;
```

`OrderEventBusTest.retryabilityClassification` 锁住了正确行为，还加了反例：

```java
// 包成 RuntimeException 之后就判不出来了
var wrapped = registeredThatThrows("x",
        new RuntimeException(new java.net.SocketTimeoutException("t"))).publish(paid());
assertFalse(((DispatchResult.Failed) wrapped.getFirst()).retryable(),
        "包成 RuntimeException 后就丢了类型信息 —— 这正是不该包起来的原因");
```

教训：**"该重试还是该告警"是业务决策，分类依据必须显式且正确，不能靠异常继承体系碰运气。**

## 怎么跑

```bash
mvn -q -pl domain-events compile exec:java -Dexec.mainClass=demo.events.Demo
```

## 测试点（11 条）

- 所有监听器都收到事件，且**按注册顺序**串行调用
- **监听器抛异常被隔离**，排在它后面的照样执行（关键断言）
- `propagateFailures` 模式下异常穿透
- 可重试判定：`SocketTimeoutException` / `IOException` / `BlacklistedException`
- **包成 `RuntimeException` 后判定失效**（反例）
- 批量汇总的三种情况（空 / 全成功 / 混合）
- `dispatchAdvice` 用无 default 的 sealed switch 逐条列出
- 已发布事件被记录，可回溯
- 观察者的固有弱点：退订要调用方自己拿 bus 引用
- sealed switch 分组，加事件类型编译不过

## 什么时候不该用它

**1. 监听器之间有顺序依赖时。** 观察者假设监听器彼此独立、顺序无关。
如果"必须先扣库存再发通知"，观察者会把这个顺序变成隐式约定，出问题时极难查。

**2. 需要可靠投递时。** 内存里的 EventBus 进程挂了就全丢了。
"支付成功必须通知下游"这类要求，得用消息队列（Kafka / RabbitMQ），
而不是 `List<Listener>`。观察者只在**进程内、不要求不丢**的场景合适。

**3. 事件被发布到很多地方时。** Spring 的 `ApplicationEvent` 是个例子：
几十个监听器、跨模块、没人说得清某个事件被谁消费了。
Java 9 的模块系统要求 `requires` 显式声明依赖，此时"广播给所有监听器"会和模块边界打架。

**4. 同步分发不够用时。** 同步分发意味着监听器的耗时直接加在主流程上。
需要异步就要自己加线程池、加失败重试队列 —— 那时你要写的已经比观察者模式本身多了。

## 延伸

- GoF 原文第 9 章 Observer
- Effective Java 第 45 条（避免依赖可变状态）—— 事件不可变不是洁癖，
  发布后可能被异步消费，能改就等于消费内容取决于时序
- `payment-gateway` 场景里的策略 + 适配器：支付方式可替换 vs 支付结果要广播，是两个不同的问题