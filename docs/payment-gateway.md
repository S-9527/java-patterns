# payment-gateway — 策略 + 适配器 + 工厂

## 场景

一笔支付请求，要能走支付宝、微信、银行卡、余额四个渠道。支付宝的第三方 SDK 和我们的接口长得完全不一样。

## 意图

三个模式各解决一件事：

- **策略** —— 支付方式可替换，一个请求只走其中一条
- **适配器** —— 把第三方 SDK 的接口翻译成我们统一的接口
- **工厂** —— 按渠道创建策略，把"需要什么配置"的知识收在一处

## 策略 vs 装饰器

对比 `money-decorator` 场景：

| | 装饰器 | 策略 |
|---|---|---|
| 包装的是 | 同一个类型的对象（`PriceRule` 包 `PriceRule`） | 另一个实现（`Alipay` 或 `Wechat`） |
| 能嵌套吗 | 能，`MemberDiscount` 包 `ShippingFee` | 不能，平行且互斥 |
| 一个请求走几条 | 全部 | 恰好一条 |

## 类清单

| 类 | 角色 |
|---|---|
| `PaymentMethod` | 策略接口，`pay()` **不声明任何异常** |
| `PaymentChannel` | enum，四种渠道 |
| `PaymentResult` | **sealed**：`Success` / `Declined` / `Errored`（带 `retryable`） |
| `AlipaySdk` | 假装的第三方 SDK，接口刻意和我们的不一样 |
| `AlipayAdapter` | 适配器，三处翻译 |
| `WechatPayMethod` / `BankCardMethod` / `BalanceMethod` | 天生适配的策略 |
| `PaymentMethodFactory` | `Map<Channel, Supplier<PaymentMethod>>` |

## enum 还是 sealed？

这是个有意的对照：

- `PaymentChannel` 用 **enum** —— 渠道是**开放的**，新增渠道不该在编译期锁死
- `OrderState` 用 **sealed**（见 `order-state` 场景）—— 状态集合是**封闭且已知**的

代价：enum **不能**在编译期保证 `PaymentMethodFactory` 的映射完整。所以用了测试来补：

```java
@Test
void everyChannelHasAFactory() {
    assertEquals(PaymentChannel.values().length, factory().factoryCount(),
            "新增 PaymentChannel 忘了在工厂里注册的话，这条断言会红");
}
```

enum 保证字段存在，测试保证映射存在，**编译器两者都做不到**。

## 适配器的三处翻译

`AlipaySdk` 刻意设计成和我们的接口完全不同：

1. **参数翻译** —— 我们的 record → SDK 特有的 `AlipayTradeRequest`，字段名是 `out_trade_no` / `total_amount`
2. **返回翻译** —— SDK 的**字符串金额（单位分）** → 我们的 `BigDecimal`（单位元）
3. **异常翻译** —— SDK 抛受检异常 → 我们返回 `PaymentResult.Errored`

第 2 处最危险。`AdapterTest` 里有 7 组往返参数化测试：

```java
@ParameterizedTest
@ValueSource(strings = {"0.01", "1.00", "99.99", "268.00", "1234.56", "0.07", "1234567.89"})
void roundTripIsLossless(String yuan) {
    var fen = AlipayAdapter.yuanToFen(new BigDecimal(yuan));
    assertEquals(0, AlipayAdapter.fenToYuan(fen).compareTo(new BigDecimal(yuan)),
            "%s 元 → %s 分 → %s 元，精度丢失了".formatted(yuan, fen, ...));
}
```

以及一条专门防 `double` 的：

```java
@Test
void usesBigDecimalNotDouble() {
    assertEquals("115", AlipayAdapter.yuanToFen(new BigDecimal("1.15")));
    double naive = 1.15 * 100;
    assertFalse((long) naive == 115, "double 路径确实会算错：" + naive);
}
```

`1.15 * 100 = 114.99999999999999`，`setScale` 后变成 114 —— 少收一分钱。

## 踩到的一个真实 bug：单位搞混

写假 SDK 时我把超时判断写成了：

```java
if (amount.compareTo(new BigDecimal("1000")) > 0) {   // ← 错
```

但 `amount` 的单位是**分**（268 元 = 26800 分）。结果 268 元被判定成"超过 1000 元"，
**所有支付宝支付都超时**，测试全红。

改成：

```java
if (amountFen.compareTo(new BigDecimal("100000")) > 0) {   // 100000 分 = 1000 元
```

这是本场景最有价值的一条教训 —— **单位错误不会有编译错误，测试未必覆盖到，
但线上会直接少收钱**。`AlipaySdk.tradePay` 的 javadoc 里记着这次。

## `create(null)` 的 NPE

写测试时发现 `factory().create(null)` 直接 NPE。原因是 `Map.of(...)` 不允许 null key，
而 `Map.get(null)` 会走 `hashCode()` 炸掉。

但**前端没传渠道字段**是完全可能的真实场景，那种情况应该提示用户，不是 500。修法：

```java
if (channel == null) {
    return Optional.empty();
}
```

顺带一个语义澄清：`null` 是**参数错误**，不算"未映射渠道"，所以不进 `unmappedChannels`。

## 怎么跑

```bash
mvn -q -pl payment-gateway compile exec:java -Dexec.mainClass=demo.payment.Demo
```

## 测试点（38 条）

`AdapterTest`（27 条）
- 元→分、分→元 参数化测试
- **往返转换无损**（7 组参数）
- `double` 路径确实算错（1.15 元）
- SDK 异常被翻译成 `Errored`，调用方不用 try-catch
- **反射断言 `pay()` 不声明任何异常**
- `Declined` vs `Errored` 的重试判定差异
- 银行卡绑定校验

`PaymentMethodFactoryTest`（11 条）
- **每个渠道都有工厂**（enum 挡不住，测试能）
- 创建的实例渠道正确
- 每次创建都是新实例（策略可能有可变状态）
- `null` 渠道优雅返回 `Optional.empty`
- 请求字段校验

## 什么时候不该用它

**1. 只有一种支付方式时。** 一个策略接口 + 一个实现 + 一个工厂，是三层空壳。
等真的有第二种方式再抽。注意本项目四个渠道是为了讲模式而凑的。

**2. 接口本来就一致时，别套适配器。** `WechatPayMethod` 直接实现 `PaymentMethod` 就行。
硬给每个渠道包一层 `XxxAdapter` 只是增加间接层，没有收益。

**3. 渠道需要注册中心时，静态工厂不够。** 生产环境里支付方式往往要动态加载
（配置中心 / 插件机制），这时候要的是 SPI 或依赖注入容器，不是 `Map`。

**4. 交易状态需要幂等重试时，只有策略是不够的。** 策略解决"走哪条路"，
"这笔请求已经处理过了吗"是另一个问题。本项目的 `idempotencyKey` 只是必填字段，
真正的幂等需要存储和状态机配合。

## 延伸

- GoF 原文第 5 章 Strategy、第 4 章 Adapter、第 7 章 Factory Method
- Effective Java 第 1 条（用静态工厂方法替代构造器）——
  `PaymentMethodFactory` 其实是这条的变体，但多了"按 key 选"的能力
- `domain-events` 场景：同样的"该不该重试"判断，在那里和这里各写了一遍。
  真实项目会抽成共享组件 —— 这也说明了重复代码出现的信号