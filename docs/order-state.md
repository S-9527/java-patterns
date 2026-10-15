# order-state — 状态模式

## 场景

订单状态机：

```
待付款 → 已付款 → 已发货 → 已完成
  ↓       ↓ ↓        ↓
  └─────→ 已取消    └→ 已退款
```

非法流转必须被拦住（比如"待付款直接发货"），而且**新增一种状态时，
所有需要处理它的地方都必须在编译期暴露出来**。

## 意图

让对象在内部状态改变时改变行为。状态模式把"当前处于什么状态"变成对象的一部分，
从而把一堆 `if (status == X)` 换成状态自己的行为。

本项目里有两套实现：

| | GoF 原版 | Java 25 式 |
|---|---|---|
| 位置 | `legacy/LegacyStates.java`、`legacy/LegacyOrder.java` | `OrderState.java`、`Order.java` |
| 状态定义 | 6 个类 + 1 个接口 | 1 个 sealed interface + 6 个 record |
| 接口方法 | 6 个（每个可能的下一步一个） | 0 个，状态自己带数据 |
| 实现方法 | 36 个 | 1 个 default 方法 |
| 抛异常的方法 | 30 个 | 0 个 |
| 订单对象 | 可变，状态被原地换掉 | 不可变，`transitionTo` 返回新对象 |

## 怎么跑

```bash
mvn -q -pl order-state compile exec:java -Dexec.mainClass=demo.orderstate.Demo
```

## 测试点

`OrderStateTest`（14 条）—— 合法流转、不可变性、非法流转、终态、退款窗口 guard（含第 7 天边界）、
"已发货后退款不受 7 天限制"、状态自带数据、`getPermittedSubclasses().length == 6`。

`EquivalenceTest`（4 条）—— **这是本项目最有说服力的一类测试**：
同一业务规则在两套实现上跑同一条路径，断言行为完全一致。

- 合法路径两套实现到达同一终态
- 非法流转两边都拒绝
- 退款窗口 guard 两边都拦、都在窗口内放行
- 唯一的行为差异：可变 vs 不可变

**这个测试当场抓出了 GoF 版的一个真 bug**：`PaidState.refund` 原本直接抛异常，
和 Java 25 版流转表里"已付款可退款"矛盾。这类语义漂移正是手写两套实现最容易出的问题。

## 什么时候不该用它

**1. 状态只有两三个，且转移逻辑一眼能写完时。** 用 `switch (status)` 就够了。
状态模式的成本是 6 个类 + 36 个方法，只有当状态数足够多、转移关系足够复杂时摊得平。
判据：你的转移表能画成一张有意义的表，值得用代码表达它。

**2. 状态之间没有行为差异，只有数据差异时。** 如果"已发货"和"已完成"的唯一区别是字段值，
那这就是普通的枚举 + 字段，不该上状态模式。**状态模式的前提是每个状态有各自的行为。**

**3. 转移关系很简单（线性或树形）时。** `待付款 → 已付款 → 已发货` 这种链条，
一个带 `next` 指针的字段就够，不需要六张表。复杂度得配得上复杂度。

**4. 状态之间需要共享大量上下文时。** GoF 原版里状态类持有订单引用，
状态之间要来回传数据，非常难写。Java 25 版把状态做成 record 缓解了不少，
但如果一个状态需要读另一个状态的三个字段，就说明状态切分本身有问题。

## 踩到的两个坑

### 1. JDK 25.0.4 上具名多标签模式编译不过

```java
case Created c2, Shipped s, Completed d, Refunded r -> false;
//                                  ^ illegal fall-through from a pattern
```

JEP 441 明确允许具名多标签模式，但 **JDK 25.0.4 会直接拒绝编译**。
无名模式 `case Created _, Shipped _ -> false;` 反而正常。

代码里所有多标签都用无名模式。这个坑在写转移表时会一次撞上 N 次
（每个内层 switch 一次）。

### 2. 每个内层 switch 必须包含"转到自身"

写转移表时最容易漏的是自转移：`Paid → Paid`、`Shipped → Shipped`。
漏掉的表现不是编译错误，而是穷尽性检查失败：

```
error: the switch expression does not cover all possible input values
```

`OrderState.canTransitionTo` 里三个内层 switch 各有一支 `_ -> false`
专门处理自转移。

## 延伸

- GoF 原文第 10 章 State —— 以及它自己对"使用状态模式还是用策略"的讨论
- JEP 441（Pattern Matching for switch，Java 21 转正）
- JEP 409（sealed classes，Java 17 转正）
- `visitor-vs-switch` 场景会看到同一个"用继承换 switch"的权衡