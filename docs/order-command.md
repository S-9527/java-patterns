# order-command — 命令 + 备忘录

## 场景

订单的每一步操作（下单、付款、发货、取消）都要能撤销。

## 意图

- **命令**（Command）—— 把"要做什么"封装成对象，于是可以入队、重试、记审计日志、撤销
- **备忘录**（Memento）—— 快照对象状态，让撤销有据可依

## 关键设计：只有需要撤销的命令才实现 `Undoable`

不是每个命令都能撤销 —— 发货撤销不了（货已经交给快递了），取消撤销不了（要反悔应该写「恢复」命令，不是给「取消」加 `undo()`）。

用一个单独的接口区分，比在接口里写 `undo()` 然后到处抛 `UnsupportedOperationException` 诚实得多。

```
下单 → 可撤销      付款 → 可撤销（但丢流水号）
发货 → 不可撤销    取消 → 不可撤销
```

**编译器能挡住误用**。写下面这行会直接编译失败：

```java
ship() instanceof OrderCommand.Undoable     // 错误：ShipOrder 不是 Undoable 的子类型
((OrderCommand.Undoable) ship()).undo(null)  // 同样编译失败
```

Java 直接拒绝类型不重叠的 `instanceof`。测试里改用反射来断言同样的事实
（`onlyUndoableCanBeUndone`）。

## 踩到的真问题：反向命令会让状态倒退

`PayOrder.undo()` 写死了"回到待付款"：

```java
@Override
public OrderState undo(OrderState current) {
    return current.withStatus(OrderState.OrderStatus.CREATED);   // 硬编码
}
```

但订单一旦发货，撤销付款就会让状态从**已发货倒退回待付款**。后果很具体：

> 货在路上 + 钱退了 + 订单显示待付款 = 用户会再来下一单

**反向命令模式没法自动解决这个问题。** `undo()` 只知道"把这次操作造成的变化倒过来"，
它不知道也不关心当前状态是什么。只能让每条命令自己判断：

```java
default boolean canUndoOn(OrderState current) { return true; }

// PayOrder 里
@Override
public boolean canUndoOn(OrderState current) {
    return switch (current.status()) {
        case CREATED, PAID -> true;
        case SHIPPED, CANCELLED -> false;
    };
}
```

`Order.undo()` 会跳过被拒绝的命令继续往下找，**并把它 pop 出栈** —— 货都发了，这笔付款再没有撤销的意义。

## 两种撤销方式

| | 反向命令 | 快照恢复 |
|---|---|---|
| 做法 | 调 `command.undo(current)` | 直接换回快照的 state |
| 实现成本 | 每条命令手写反向逻辑 + `canUndoOn` | 极简 |
| 语义精度 | 精确 | **有损** |
| 审计 | 保留完整痕迹 | 痕迹消失 |

Demo 里两种都实现，跑同一份数据：

```
A（反向命令）
  初始化 / 下单 SO-3002 / 待付款 → 已付款 / 已付款 → 待付款

B（快照恢复）
  初始化 / 下单 SO-3002
```

**状态一样，history 完全不同。** 反向命令保住了"付款发生过又被撤销"的痕迹，
快照恢复把 history 整个换回去了，审计时看不出发生过什么。

**生产环境通常选反向命令** —— 审计比实现简单重要。

## 备忘录很便宜，因为状态是不可变 record

```java
public record Snapshot(String label, OrderState state) { }
```

存一个引用就完成了，不需要手写深拷贝。如果 `OrderState` 是可变的对象，
这里就得多写一堆复制代码，而且**很容易漏掉某个字段** —— 漏了就会出
"撤销了一半"的诡异 bug。

`snapshotsAreCheapBecauseImmutable` 断言旧快照不受后续操作影响，这是不可变性的直接证据。

## ⚠ 撤销警告不总是准的

`undoWarning()` 写在命令上，描述的却是有状态的业务事实：

```
第一次撤销警告：撤销付款会丢失交易流水 PAY-888
撤销后警告：null
再付一次，警告：撤销付款会丢失交易流水 PAY-888    ← 一模一样
```

但第二次其实不准确 —— 财务那边能看到两笔流水。
**警告写在命令上（无状态），却描述了有状态的业务事实。**
真实系统应该由备忘录记录"这次撤销丢了什么"，而不是命令自报。

## 怎么跑

```bash
mvn -q -pl order-command compile exec:java -Dexec.mainClass=demo.command.Demo
```

## 测试点（16 条）

- 正常执行 / 反复撤到栈空
- **只有可撤销的命令才进栈**
- **已发货后撤销付款被跳过**（状态倒退防护）
- **被跳过的命令弹出栈**
- 栈空后再撤抛异常
- **反向命令 vs 快照恢复：状态同、history 不同**
- 快照恢复会清空撤销栈
- 撤销警告 / 警告重复出现的不准确性
- **Undoable 是单独接口，编译器挡住误用**（反射断言）
- 快照不受后续操作影响（不可变性）
- 快照历史完整 / 取消记录原因

## 什么时候不该用它

**1. 操作只有两三步且不需要撤销时。** 命令模式把一次方法调用变成一个类，
是纯粹的额外成本。判据：有没有"入队 / 重试 / 审计 / 撤销"里的任一需求。

**2. 状态比命令复杂时。** 本模块的 `canUndoOn` 就是警报 —— 当"哪些命令在哪些状态下可撤销"
的规则本身有十几条时，你不缺命令模式，你缺的是状态机（见 `order-state` 场景）。

**3. 需要"重做"时。** 命令模式的经典配套还有 `redo`，但它要求命令**严格可逆**。
业务上撤销付款靠的是退款流程（会发短信、动账），不是"把状态改回去"。
`redo` 栈在真实业务里几乎总是错的。

**4. 审计要求高时更该用备忘录。** 反向命令保住了 history，但如果中途有并发操作，
"撤销"到底撤到哪个点会变得含糊。**这时要么加锁，要么改用带版本号的快照。**

## 延伸

- GoF 原文第 7 章 Command、第 14 章 Memento
- `order-state` 场景 —— 当转移规则复杂到 `canUndoOn` 写不下时，该换状态机了
- Effective Java 第 42 条（用 EnumSet 而不是位标志）
  —— 这里 `canUndoOn` 本可以做成 `EnumSet<OrderStatus>`，可读性会更好