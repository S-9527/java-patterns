# order-builder — 建造者

## 场景

订单 = 5 个必填 + 5 个可选字段。三种写法对比。

## 核心结论

**渐进式 Builder** —— 同一个 builder 实例可以被多次 `build()`，
生成多个变体，不需要每次都从头设置 6 个字段。

## 三种写法对比

### 伸缩构造器

```java
new TelescopingOrder("ORD-001", "张三", items, BigDecimal.valueOf(599),
        OrderStatus.CREATED);
// 如果要用 couponCode，要换一个 10 参数的构造器，中间 4 个 null
```

**痛点**：参数多，调用方看不出哪些是必填，第 5 个参数之后全是 null。
如果有 10 个可选字段，要写 11 个重载构造器。

### 可变 JavaBean

```java
var order = new MutableOrder();
order.setId("ORD-001");
order.setCustomerName("张三");
// ...
```

**痛点**：没有不可变性，没有校验。忘了设字段，到用的时候才发现 NPE。
测试时容易出残留状态。

### 渐进式 Builder

```java
var order = Order.builder()
        .id("ORD-001")
        .customerName("张三")
        .items(items)
        .total(BigDecimal.valueOf(599))
        .couponCode("SAVE10")
        .build();
```

**优势**：
- 必填校验集中在 `build()`，错误信息一次报全
- 不可变结果 —— record
- 同一个 builder 可以多次 `build()`，生成变体
- `toBuilder()` 做局部修改

## 渐进式 vs 独立式 Builder

**渐进式**（本项目）：同一个 builder 实例可以被多次 `build()`。

```java
var builder = Order.builder().id("A").customerName("张三").items(items).total(BigDecimal.ONE);
var order1 = builder.build();
builder.couponCode("SAVE10");
var order2 = builder.build();  // builder 状态保留
```

**独立式**（Lombok `@Builder`）：`build()` 消耗 builder，不能复用。

渐进式适合"生成多个变体"的场景。独立式更常见，因为更符合函数式思维。

## toBuilder()

```java
var modified = original.toBuilder()
        .status(OrderStatus.PAID)
        .build();
```

`toBuilder()` 返回**新的** builder 实例（不是同一个），所以改一个不影响另一个。
测试 `toBuilderReturnsNewBuilder` 断言了这一点。

## 自定义 Record 访问器

`Order` record 的可选字段在 record 组件里是 `String couponCode`，
但自定义访问器 `couponCode()` 返回 `couponCode == null ? "" : couponCode`。

**Java 允许 record 覆盖自动生成的访问器**。这是让可选字段不暴露 null 的标准做法。

测试 `customAccessorsOverrideGenerated` 断言了这个行为。

## 踩到的坑

**1. `"a" + "b".formatted(...)` 的运算符优先级。**
`.formatted()` 只作用在 `"b"` 上，然后 `"a"` 拼接结果。
必须加括号：`("a" + "b").formatted(...)`。

**2. Record 的 `toString()` 显示原始字段值，不是自定义访问器的返回值。**
Order 的 `toString()` 最初用 record 自动生成的，显示 `notes=null` 而不是 `notes=''`。
必须自定义 `toString()` 并调用自定义访问器。

**3. `Objects.requireNonNull` 的消息是中文，不是字段名。**
`mutable.toOrder()` 抛的 NPE 消息是 `"客户名不能为空"`，不是 `"customerName"`。
测试断言要匹配实际消息。

## 类清单

| 类 | 角色 |
|---|---|
| `Order` | 不可变 record，含渐进式 Builder |
| `Builder` | 渐进式 Builder，`build()` 不消耗自己 |
| `Item` | 商品行项目，record |
| `OrderStatus` | 枚举 |
| `OrderPriority` | 枚举 |
| `TelescopingOrder` | 伸缩构造器反例 |
| `MutableOrder` | 可变 JavaBean 反例 |

## 怎么跑

```bash
mvn -q -pl order-builder compile exec:java -Dexec.mainClass=demo.builder.Demo
```

## 测试点（18 条）

- 必填字段完整时 build 成功
- ⚠ 缺失必填字段时错误信息一次报全
- ⚠ 缺失一个字段时报一个，不连带
- 可选字段不填时用默认值
- 渐进式 builder 可以多次 build()
- 必填校验在 build() 不在 setter 里
- toBuilder 保留所有字段
- toBuilder 后修改单个字段
- toBuilder 往返一致性
- ⚠ toBuilder 返回的是新 builder，不是同一个
- ⚠ 伸缩构造器参数多（2 个重载，5+10 个参数）
- 短构造器自动填 null
- ⚠ 可变 JavaBean 没有校验
- ⚠ 可变 JavaBean 缺失字段时 toOrder() 抛 NPE
- compact constructor 校验必填字段
- 可选字段 null 时通过访问器返回默认值
- ⚠ 自定义访问器覆盖了 record 的生成访问器
- Item 数量必须为正

## 什么时候不该用建造者

- **字段少（≤ 3 个）** —— 直接写构造器就行
- **字段多但都有默认值** —— record 的紧凑构造器 + 默认参数（Java 25 还没有）
- **对象是可变且需要逐步修改** —— 那用 JavaBean，别用 builder
- **框架自动生成** —— Spring 的 `BeanDefinitionBuilder`、Lombok 的 `@Builder`
  已经做了这件事，自己写一套是重复劳动
