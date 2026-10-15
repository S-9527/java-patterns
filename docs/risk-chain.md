# risk-chain — 责任链

## 场景

下单前的风控校验，四个环节串起来：

```
黑名单 → 限购 → 库存 → 单笔限额
```

## 意图

让多个对象都有机会处理同一个请求，把"谁处理、什么顺序"从客户端代码里解耦出来。

## 关键决策：遇到拒绝时**不短路**

教科书责任链是"命中就停"。但下单风控的真实需求往往是**一次性告诉用户所有问题** ——
否则用户改完黑名单才发现又超限购，来回三四轮体验很差。

本项目用 `Mode` 参数把两种语义都跑一遍：

```
模式 A  SHORT_CIRCUIT   → 1 条规则未通过：[拒绝（规则 #0 黑名单：用户在黑名单中）]
模式 B  COLLECT_ALL     → 3 条规则未通过：[拒绝（#0 黑名单…）, 拒绝（#1 限购…）, 中断（库存：库存服务超时）]
```

**但 `Aborted` 无条件终止**，即使是 COLLECT_ALL。理由：某条规则依赖的服务挂了，
后面规则的结论不可信，继续跑出来的结果没有意义。

## 类清单

| 类 | 角色 |
|---|---|
| `RiskRule` | 接口，**只有一个** `check` 方法 + 4 个实现（都是 record） |
| `RiskChain` | 链的编排，持 `List<RiskRule>`，带 `Mode` |
| `RiskResult` | sealed，四种结局：`Passed` / `Rejected` / `Aborted` / `ChainOutcome` |
| `RiskContext` | 上下文，让规则反查自己在链上的位置 |
| `Order` / `Line` / `Money` | 数据 |

## 为什么 `Aborted` 要和 `Rejected` 分开

两者的重试策略完全相反：

- `Rejected` —— 用户明确不该下单。**重试一万次还是被拒**，应该直接提示用户。
- `Aborted` —— 风控服务挂了、库存超时。**应该重试或降级**，提示用户"系统繁忙"没意义。

把它们混成一个"失败"，就丢掉了这个决定重试策略的信息。

`RiskChainTest.abortedIsNotRejected` 用一个 sealed 穷尽的 switch 演示了这个区分
怎么变成可编译的类型检查。

## 规则抛异常时不会炸掉整条链

```java
try {
    result = rule.check(order, context.at(i));
} catch (RuntimeException e) {
    result = new RiskResult.Aborted(rule.name(), e.getMessage());
}
```

责任链最容易出的事故：某条规则抛异常，整条链炸掉，连已经知道的问题都丢了。
这里把异常降级成一种结果，让失败信息能汇合。

## 怎么跑

```bash
mvn -q -pl risk-chain compile exec:java -Dexec.mainClass=demo.risk.Demo
```

## 测试点（14 条）

- 正常订单全过 / 黑名单命中
- **限购把所有行的数量相加**，不是看单行
- 库存不足列出**所有**缺货行
- SHORT_CIRCUIT vs COLLECT_ALL 的条数差异
- **Aborted 无条件终止**
- 规则抛异常降级成 Aborted 而不是炸掉
- `Aborted` / `Rejected` 用 sealed switch 区分重试策略
- 顺序改变结果（用 SHORT_CIRCUIT 模式断言，COLLECT_ALL 下顺序只影响列表顺序）
- 规则知道自己排第几，但不知道自己是谁
- **加规则不用改已有规则**（用一个匿名 spy 规则验证）
- 空链拒绝构造 / `ChainOutcome.failures` 不可变 / `RiskContext` 反查位置

## 什么时候不该用它

**1. 处理顺序固定且规则数量很少时（2~3 条）。** 一串 `if` 或一个 `switch` 更直白。
责任链的价值在于"顺序可变 + 数量多"，两条规则的链不值得造。

**2. 处理逻辑有严格先后依赖、且不能被打断时。** 比如"先扣库存再扣款"，
责任链的"某条规则可以不传给下一条"这个特性反而会制造出危险的中间状态。
这种情况应该用顺序编排（见 `pipeline` 场景的模板方法）。

**3. 每条规则需要共享大量可变上下文时。** 责任链的规则互不知情，
要传数据就得塞进 context，上下文会逐渐膨胀成"上帝对象"。

## 这个场景刻意选了教科书的反面写法

**教科书责任链**：`rule.setNext(nextRule)`，然后每条规则手写 `next.handle(ctx)`。

```java
@Override public RiskResult handle(Ctx ctx) {
    if (rejected(ctx)) return reject(ctx);
    return next.handle(ctx);   // ← 手工往下传，漏掉就静默断链
}
```

**本项目用的是"链持规则"**：规则只有一个 `check` 方法，完全不知道自己在一���链上。

| | 教科书（规则持链） | 本项目（链持规则） |
|---|---|---|
| 规则需要知道链存在吗 | 需要，多一个 `next()` 要维护 | 不需要 |
| 顺序能动态组吗 | 不能，焊死在构造代码里 | 能，`RiskChain.of(List)` |
| 加一条规则要改什么 | 改构造代码 | 加一个类 |
| 漏传 `next.handle()` 的后果 | 静默断链，无编译期检查 | 不可能漏，链统一驱动 |
| 规则能自定义是否往下传 | 能 | 不能 |

**丢掉了灵活性，但换来了可动态组装和不会静默断链。**
多数场景不需要"规则可自定义往下传"这种灵活性 —— 真正需要它的场景，
通常用编排式的流程（`pipeline` 场景）更合适。

注意 `RiskChain.legacyChainedOrder` 这个方法只是**演示**这种组装方式的存在，
它的实现刻意写得很笨拙（手工填 `callLog`），因为教科书写法的本质就是手工传递。

## 延伸

- GoF 原文第 5 章 Chain of Responsibility
- `pipeline` 场景 —— 同一个"多步骤流程"的另一种解法，对比着看很有价值
- Effective Java 第 41 条（用标记接口而不是引用类型做类型判断）
  —— `RiskResult` 用 sealed 枚举结局而不是给各结果类加标记方法