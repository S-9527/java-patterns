# visitor-vs-switch — 访问者 vs sealed+switch（等价性测试）

## 场景

形状计算（面积 + 周长），用两种写法做同一个事。

## 核心结论：访问者在 Java 里几乎可以退休

sealed + switch 是 GoF 访问者的**同一件事的更短写法**。

| | 访问者 | sealed + switch |
|---|---|---|
| 新增形状 | 所有访问者都要改（不强制，漏了编译通过） | 所有 switch 都要改（编译器强制，漏了编译不过） |
| 新增操作 | 加一个访问者，已有形状不用改 | 加一个方法，已有形状不用改 |
| 接口数 | 1（ShapeVisitor）+ N 个 visit 方法 | 0（sealed 保证穷举） |

## 等价性测试

这是本项目**最高价值的测试** —— 断言两种写法对同一输入产生完全一致的结果：

```java
@Test
@DisplayName("⚠ 批量等价性：100 个随机形状，两种写法结果完全一致")
void bulkEquivalence() {
    var shapes = generateShapes(100);
    var visitor = new MetricsVisitor();

    for (var shape : shapes) {
        var visitorResult = shape.accept(visitor);
        var switchResult = MetricsViaSwitch.compute(shape);
        assertTrue(visitorResult.isEquivalent(switchResult),
                "⚠ 对形状 %s，两种写法结果不一致".formatted(shape));
    }
}
```

`ShapeResult.isEquivalent` 用 `1e-9` 容差比较浮点数 —— 足够严格，不会因为
`Math.PI * r * r` 的浮点误差误判。

## sealed 的穷举检查

```java
public static ShapeResult compute(Shape shape) {
    return switch (shape) {
        case Circle c -> { ... }
        case Rectangle r -> { ... }
        case Triangle t -> { ... }
        // ⚠ 没有 default 分支 —— sealed 保证了穷举。
        //   加了新形状后，编译器会强制所有 switch 补上新 case。
    };
}
```

`sealedGuaranteesExhaustiveness` 测试通过 `Shape.class.getPermittedSubclasses()`
断言了 Shape 有 3 个 permits。

## 访问者的代价：接口膨胀

```java
// 访问者接口的方法数 = 形状数
// 10 种形状 + 5 种访问者 = 50 个方法
var visitorMethods = ShapeVisitor.class.getDeclaredMethods().length;  // 3
var shapeCount = Shape.class.getPermittedSubclasses().length;          // 3
assertEquals(shapeCount, visitorMethods);
```

## ⚠ accept 返回 void 的经典错误

最初 `Shape.accept` 声明为 `void`，但测试和 Demo 都需要返回值：

```java
// ❌ 最初的写法
void accept(ShapeVisitor visitor);

// ✅ 修正
ShapeResult accept(ShapeVisitor visitor);
```

对应地，每个形状的实现也要改：

```java
@Override
public ShapeResult accept(ShapeVisitor visitor) {
    return visitor.visit(this);  // 而不是 visitor.visit(this);（丢弃返回值）
}
```

这是 GoF 访问者最常见的错误 —— accept 丢弃了 visit 的返回值。
GoF 原文里 accept 也是 void，因为访问者模式在 GoF 时代主要是做**副作用**（打印、计数），
不是做**计算**。

## ⚠ 局部 record 实现多方法接口的编译问题

最初用局部 record 定义临时访问者：

```java
record DescriptionVisitor implements ShapeVisitor { ... }
```

编译器报 `'(' expected`。改成匿名内部类后编译通过。
这可能是 JDK 25 的局部 record 限制。

## ⚠ 三角形生成的边界情况

最初随机生成三角形时，`c = 0.1 + random.nextDouble() * (Math.min(a + b - 0.2, 10))`
在 `a` 或 `b` 很小时可能违反三角不等式。

修正：`c` 必须在 `(|a-b|, a+b)` 之间：

```java
var a = 1.0 + random.nextDouble() * 5;
var b = 1.0 + random.nextDouble() * 5;
var lower = Math.abs(a - b) + 0.1;
var upper = a + b - 0.1;
var c = lower + random.nextDouble() * (upper - lower);
```

## 类清单

| 类 | 角色 |
|---|---|
| `Shape` | sealed 接口，permits Circle/Rectangle/Triangle |
| `Circle` / `Rectangle` / `Triangle` | 形状记录 |
| `ShapeVisitor` | 访问者接口，3 个 visit 方法 |
| `MetricsVisitor` | 面积/周长访问者 |
| `MetricsViaSwitch` | 面积/周长 switch 写法 |
| `ShapeResult` | 计算结果，带 `isEquivalent` 浮点容差比较 |

## 测试点（20 条）

- **⚠ 批量等价性：100 个随机形状，两种写法结果完全一致**
- 圆/矩形/三角形单独等价性断言
- 浮点数精度（1e-9 容差）
- 每个形状都实现了 accept
- ⚠ 新增形状后，所有访问者都要加 visit 方法
- 访问者可以做不同的计算（描述访问者）
- switch 处理所有形状类型
- ⚠ sealed 保证穷举 —— 没有 default 分支
- switch 的编译器穷举检查
- ⚠ 访问者接口的方法数 = 形状数（接口膨胀）
- ⚠ 新增形状的成本对比
- 访问者的优势：新增操作 → 加一个访问者，已有形状不用改
- Circle/Rectangle/Triangle 输入校验
- ShapeResult 等价性

## 延伸

- GoF 原文第 31 章 Visitor
- `order-state` 场景 —— sealed + switch 的另一个用例（状态机）
- 延伸阅读：为什么访问者在 GoF 原文里被标注为"很少使用"
