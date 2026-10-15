# money-decorator — 装饰器

## 场景

一笔订单总价不是商品单价之和，而是层层加工出来的：

```
商品小计 → 会员折扣 → 优惠券 → 运费 → 税
```

每一步都在上一步结果上做算术，而且**每一步都可能被单独去掉或加上**。
去掉优惠券，不该动到运费和税的任何一行代码。

## 意图

动态地给对象添加职责。装饰器与被装饰对象**实现同一个接口**，于是可以层层包裹、任意组合。

## 类清单

| 类 | 角色 |
|---|---|
| `PriceRule` | sealed 接口，价格链上的一环。`Money apply(Money)` |
| `ItemSubtotal` | 链的起点，从购物车算小计 |
| `MemberDiscount` | 装饰器：持有 `next`，先问下游再打折 |
| `CouponDiscount` | 装饰器：`sealed` 满减券 / 折扣券，用模式匹配 switch 分派 |
| `ShippingFee` | 装饰器：加法而非乘法，但形状一样 |
| `Tax` | 装饰器：通常在最外层 |
| `Money` | 金额值对象，`BigDecimal` + 两位小数规整 |
| `LineItem` | 购物车一行 |
| `Demo` | 逐层包裹展示洋葱 |

## 怎么跑

```bash
mvn -q -pl money-decorator compile exec:java -Dexec.mainClass=demo.money.Demo
```

## 测试点

`MoneyTest`（7 条）
- **double 连 0.1 都存不精确** —— 金额必须用 `BigDecimal` 的原因
- **`BigDecimal.equals` 对 scale 敏感**：`1.0` 和 `1.00` 值相等但 equals 为 false。
  放进 record 组件会让断言莫名其妙挂掉，所以 `Money` 构造器统一 `setScale(2, HALF_UP)`
- 币种不同直接拒绝运算，不做隐式换汇
- `HALF_UP` 而非 `HALF_EVEN` 的舍入
- `percentageOff(10)` 是乘 0.9，不是减 0.1

`PriceChainTest`（9 条）
- 每包一层结果变一次，已有层不改
- 去掉一层 = 少包一个
- **顺序影响结果**：税在运费外 vs 运费在税基外，差 10.40 = 80 × 13%
- 满额包邮门槛按折后金额判断
- 券未达门槛时这一层是 no-op
- 减免额不会把金额打成负数
- 空购物车走完整条链得到 0

## 什么时候不该用它

**1. 只有一层包装的时候别用。** 装饰器值得的成本是"组合爆炸"。
如果全局只有 `加运费` 这一种扩展，直接在定价方法里加三行，比造一套接口 + 4 个实现类便宜得多。
判据：你能说出"以后可能至少有 N 种排列组合"，才值得上装饰器。

**2. 装饰器链太深时。** 这里最深 5 层，已经开始难读了。
真实的计价链经常有十几层，这时应该考虑把中间结果显式建模成 `PriceBreakdown` record，
而不是继续加深嵌套 —— 嵌套深度超过 4~5 层，可读性损失通常超过它带来的灵活性收益。

**3. 接口签名被算术绑架时。** `Money apply(Money)` 让 `ItemSubtotal` 必须接受一个它不用的参数
（链的起点传 `Money.zero` 进去，再丢掉）。另一种设计是传 `PricingContext`，但那样每条规则
都要自己从 context 里掏 `next`，耦合更重。**两种方案都有别扭之处，说明"把任意规则串成一条链"
这个需求本身可能该重新审视。**

**4. 各层做的是不同类型的事时。** 这里折扣是乘法、运费是加法、税又是乘法，
但它们共用同一个 `apply(Money)` 签名，所以能被装饰器串起来。
如果两条规则的参数和返回值类型都不一样（比如一条要 `Address`、一条要 `User`），
那就该用不同的接口，装饰器不适用。

## 这个场景暴露的一个真实业务坑

优惠券满减门槛是按**折前还是折后**金额判断的？

代码里 `CouponDiscount` 包在 `MemberDiscount` 外面，所以它看到的是**折后**金额。
小计 207.80、券门槛 200、会员 GOLD 打 9 折 → 折后 187.02 < 200 → **券被判为不可用**。

把 `CouponDiscount` 包到 `MemberDiscount` 外面，券就能看到折前的 207.80 → 券生效。

**装饰器链的套法会改变业务语义。** 这不是技术细节，是必须显式做出的产品决策。
`PriceChainTest.couponThresholdIsEvaluatedAfterMemberDiscount` 专门锁住了这个行为。

## 延伸

- GoF 原文第 2 章 Decorator
- Effective Java 第 6 条（依赖注入优于硬编码引用）—— 装饰器本质是一种运行期注入
- `Coin` 在 `visitor-vs-switch` 场景里会遇到同一个"嵌套 vs 显式"权衡