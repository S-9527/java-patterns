# catalog-flyweight — 享元（反面教材）

## 场景

10 万个 SKU，每个都带一份"分类 / 品牌 / 币种 / 标价"元数据。

## 意图

通过共享一部分对象来减少内存占用。

## 核心结论：在 Java 里享元通常就是两行代码

```java
Map<MetaKey, ProductMeta> pool = new ConcurrentHashMap<>();
pool.computeIfAbsent(key, k -> new ProductMeta(...));
```

没有工厂类，没有池接口，没有 `Flyweight` 基类。

**GoF 把它叫一个模式，是因为 1990 年代的语言没有 `computeIfAbsent`。**
换个说法：这个模式在 Java 里已经"内联"进语言特性里了。

`ProductMetaFactory` 的类名就是这句话 —— 它看起来像享元工厂，实际只是个包了 Map 的壳。

## 什么时候 Map 不够用

1. 池需要**淘汰策略**（LRU / LFU / TTL）
2. 池大小需要**硬上限**，否则 key 无界增长就是 OOM
3. 需要**命中率统计**作为业务指标

这才值得手写一个池 —— 见 `BoundedMetaPool`。代价是 LRU 要在每次访问时改顺序，
引入锁竞争和内存开销。`computeIfAbsent` 一行搞定的事，那里写了 60 行，
而且只是"最终一致"（并发下可能短暂超限）。

**真到生产环境，用 Caffeine 之类的现成池，别自己写。**

## 什么时候享元根本就是错的

**1. 元数据本来就少，重复也不多。** 本项目 20 种元数据就算不共享也才几 MB。
值得的是"100 万份副本"和"20 份"的差别，不是 20 和 4 的差别。

**2. 对象不是共享安全的（可变）。** 共享 + 可变 = 所有人一起被改。
**"共享"和"不可变"是绑定的**，这是享元唯一的硬性前提。

**3. "共享"这件事本身没有业务含义。**
适合：地区表、币种表、枚举字典这类**天然全局**的只读数据。
不适合：订单、商品这类**本来就应该各有各的**数据。

**4. 淘汰逻辑比省下的内存还复杂。** 池里的逻辑比直接 `new` 一份还容易出错，那就别共享。

## 类清单

| 类 | 角色 |
|---|---|
| `ProductMeta` | **内蕴状态**，不可变 record，共享的部分 |
| `Product` | **外蕴状态**，持有 `ProductMeta` 引用 |
| `MetaKey` | 池的键，也是 record |
| `ProductMetaFactory` | 「享元池」= Map + computeIfAbsent |
| `BoundedMetaPool` | **真正需要手写享元**的形态：上限 + LRU + 统计 |

## 用 record 做池键

避免拼字符串的冲突边界情况：

```java
// 拼 category + "/" + brand 的话，("a/b","c") 和 ("a","b/c") 会撞
assertFalse(new MetaKey("a/b", "c").equals(new MetaKey("a", "b/c")));
```

## 踩到的一个坑：只查长度不等于校验

`ProductMeta` 原本只检查 `currencyCode.length() == 3`。但 **`"RMB"` 也是 3 个字符**，
它不是有效的 ISO 4217 代码（人民币是 `CNY`）。写测试时才发现。

真正校验要用 `java.util.Currency.getInstance`：

```java
try {
    java.util.Currency.getInstance(currencyCode);
} catch (IllegalArgumentException e) {
    throw new IllegalArgumentException(
            "无效的 ISO 4217 币种代码：%s（人民币是 CNY，不是 RMB）".formatted(currencyCode));
}
```

顺带修正了 Demo 里的一处取模塌陷 —— 两重取模 `% 20 % 4` 让"20 种元数据"实际只生成 4 种。

## 怎么跑

```bash
mvn -q -pl catalog-flyweight compile exec:java -Dexec.mainClass=demo.flyweight.Demo
```

## 测试点（14 条）

- 同一 key 拿到**同一实例**（享元核心）
- 不同 key 不同实例
- **10 万 SKU 只产生 20 份元数据**（池大小与 SKU 数解耦）
- 元数据不可变（无 setter，反射断言）
- **200 个虚拟线程并发取同一 key 只建 1 个对象** —— `computeIfAbsent` 的并发保证
- 命中/未命中统计
- 有上限的池会淘汰（8 种放进去，上限 3，淘汰 5 次）
- 命中率计算 / 空池命中率返回 0 而不是 NaN
- **无界池 vs 有界池**（无界池放 1000 个就 1000 个，有界的停在 10）
- record 池键避免了字符串拼接冲突
- 币种校验（**RMB 不是合法 ISO 代码**）

## 延伸

- GoF 原文第 6 章 Flyweight
- `money-decorator` 场景的 `Money` —— 同样是"大量小对象"，但那里的答案是
  让对象变小（record），而不是共享
- 延伸阅读：为什么 Java 没有内置的缓存池（Caffeine 之类的东西是自己长出来的）