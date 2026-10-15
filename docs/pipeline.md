# pipeline — 模板方法

## 场景

数据导入流水线，骨架固定、每步实现不同：

```
open → readRaw → parse → afterParse → validate → transform → write → close
```

## 意图

在父类里定死算法骨架，让子类去实现其中一步或几步。

## 类清单

| 类 | 角色 |
|---|---|
| `ImportPipeline` | **抽象基类**，`run()` 是 `final` 的骨架 |
| `CsvImportPipeline` | 覆写全部必选步骤 + 两个钩子 |
| `TsvImportPipeline` | **只实现 3 个抽象方法**，钩子全用默认 |
| `StrategyPipeline` | 同一件事的策略版写法，用来对照 |
| `SourceRecord` | 流水线里流动的不可变 record |
| `StepReport` | 每步的进出条数和说明 |

## 钩子分三类

| 类型 | 方法 | 子类不覆写会怎样 |
|---|---|---|
| 抽象 | `readRaw` / `parse` / `validate` | **编译不过** |
| 可选钩子 | `open` / `close` | 什么都不做 |
| 扩展钩子 | `afterParse` / `transform` | 原样返回入参 |

`TsvImportPipeline` 只写了 3 个方法就跑完 6 步 —— 这是模板方法相对策略的实际优势：
**新加一步不需要每个子类都改**。如果 `afterParse` 是策略对象，TSV 也要被迫提供一个"什么都不做"的实现。

## 怎么跑

```bash
mvn -q -pl pipeline compile exec:java -Dexec.mainClass=demo.pipeline.Demo
```

## 测试点（14 条）

`PipelineTest`（10 条）
- **`run()` 是 `final`**（反射断言 `Modifier.isFinal`）—— 这是模板方法的契约
- 步骤顺序永远是那 6 步，子类改不了
- 注释行和空行被跳过，坏行**带 error 标记保留**
- 校验不删行，只打标记
- `write` 报告记的是**真正写出的条数**，不是传进去的
- 钩子默认实现可用 / 钩子可 extend 也可完全替换
- `run()` 可重复调用（报告在开头 `clear`）
- 报告不可变 / 抽象方法编译期强制 / `open`、`close` 按序调用

`StrategyVsTemplateTest`（4 条）
- 两版最终写出的有效记录数**完全一致**
- 两版对坏行的判定一致
- 归一化效果一致
- **策略版能在运行期换掉某一步**，模板方法做不到

## 什么时候不该用它

**1. 流程本身是变量时。** 模板方法把顺序焊死在 `final` 方法里。
如果你需要"这次导入先校验后归一化，下次反过来"，模板方法做不到 ——
只能新建一个子类，或者改用策略。

**2. 步骤少于 3 步时。** 三步流程用继承不如用三个 lambda 串起来。
继承的代价（子类耦合、无法组合复用）摊不到这么短的流程上。

**3. 需要在运行期替换某一步时。** 这是模板方法的硬限制。
`StrategyVsTemplateTest.strategySwappableAtRuntime` 演示了策略版怎么做到。

**4. 继承树会变深时。** 一旦出现 `A` → `B` → `C` → `D` 的继承链，
调试时要跳四层才能看到实际执行的代码。Spring 那些"模板方法定流程 + 策略填步骤"的复杂框架，
往往就是被继承树逼出来的。

## 踩到的一个坑：`note()` 的调用时机

子类想在步骤里记一句话：

```java
@Override protected List<SourceRecord> validate(List<SourceRecord> records) {
    ...
    note("validate", "标记了 4 条不合格记录");
    return result;
}
```

骨架必须**先占位 `report()`，再执行步骤，最后 `repout()` 回填真实条数**：

```java
report("validate", extended.size(), extended.size());   // 占位
var checked = validate(extended);                        // 子类在这里 note()
repout("validate", checked.size());                      // 回填
```

反过来（先执行步骤再 `report`）的话，`note()` 找不到对应记录就会扑空。
`note()` 现在直接抛 `IllegalStateException` 而不是默默插一条占位 ——
默默插入会把步骤序列搞乱（`[readRaw, readRaw, parse, ...]`），比报错更难查。

这个顺序问题是写测试时才发现的：步骤序列出现重复，说明有步骤往报告里插了额外的记录。

## 模板方法 vs 策略

| | 模板方法（继承） | 策略（组合） |
|---|---|---|
| 骨架 | `final` 方法，顺序锁死 | shell 里写死，但可整体替换 |
| 加一步 | 改父类，所有子类自动有 | 改 shell，所有策略自动有 |
| 换掉某一步 | 要新建子类（编译期） | 传个新 lambda（运行期） |
| 换掉顺序 | 要新建子类 | 要新建 shell |
| 运行期动态选 | 不行 | 行 |
| 代价 | 继承耦合 | 参数列表变长 |

**策略版的一个真实劣势在 `StrategyPipeline` 里保留着**：`Step` 接口有**两个**参数 ——
输入记录和一个共享的 `List<StepReport>`。策略是纯函数式的，想让它往报告里写东西，
就得把可变状态从外面递进去。模板方法的 `note()` 是父类方法，子类直接调，不需要传状态。

选哪个：
- 流程永远不变、只是步骤实现不同 → 模板方法（继承树浅）
- 需要运行期换步骤，或流程本身是变量 → 策略
- 两者都要 → 模板方法定流程，策略填步骤（Spring 就是这么干的）

## 延伸

- GoF 原文第 8 章 Template Method —— 以及它对"优先用组合而不是继承"的免责声明
- `risk-chain` 场景 —— 同一个"多步骤流程"的另一种解法（链式、按需短路）
- Effective Java 第 18 条（用抽象类代替继承）