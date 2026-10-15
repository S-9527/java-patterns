# java-patterns

用 **14 个互相独立的小场景**，讲清楚 GoF 设计模式在 Java 25 上的正确用法，以及配套的现代 Java 特性。

不是完整业务应用 —— 每个场景只有刚好够说明模式的那几个类。

---

## 快速开始

```bash
mvn clean test                                    # 全部测试
mvn -q -pl money-decorator compile exec:java \
   -Dexec.mainClass=demo.money.Demo              # 单跑一个场景

mvn -q -pl runner -am package                    # 全部场景打成单个可执行 jar
java -jar runner/target/java-patterns.jar        # 跑全部
java -jar runner/target/java-patterns.jar money  # 只跑名字含 money 的
```

单模块独立运行（验证零外部依赖）：

```bash
cd risk-chain && mvn test
```

## 场景索引

按教学顺序排列：结构型 → 创建型 → 行为型 → 高阶争议 → JDK 特性。

| # | 模块 | 设计模式 | 主要 Java 特性 | 文档 |
|---|---|---|---|---|
| 1 | `money-decorator` | 装饰器 | record、sealed interface、switch 表达式 | [docs](docs/money-decorator.md) |
| 2 | `order-builder` | 建造者 | record 校验构造器、`toBuilder()` | [docs](docs/order-builder.md) |
| 3 | `order-state` | 状态 | sealed + switch 模式匹配穷尽分派 | [docs](docs/order-state.md) |
| 4 | `risk-chain` | 责任链 | sealed 结果类型、链式装配 | [docs](docs/risk-chain.md) |
| 5 | `pipeline` | 模板方法 | final 模板方法、钩子 | [docs](docs/pipeline.md) |
| 6 | `domain-events` | 观察者 | 函数式接口、`List.copyOf` | [docs](docs/domain-events.md) |
| 7 | `payment-gateway` | 策略 + 适配器 + 工厂 | sealed 策略族 | [docs](docs/payment-gateway.md) |
| 8 | `checkout-facade` | 门面 + 中介者 | 文本块画依赖图 | [docs](docs/checkout-facade.md) |
| 9 | `order-command` | 命令 + 备忘录 | lambda 实现命令 | [docs](docs/order-command.md) |
| 10 | `catalog-flyweight` | 享元 | `computeIfAbsent` 建池 | [docs](docs/catalog-flyweight.md) |
| 11 | `singleton-config` | 单例 | 枚举 / 静态持有者 / volatile DCL | [docs](docs/singleton-config.md) |
| 12 | `dynamic-proxy` | 代理 | `Proxy` + `InvocationHandler` | [docs](docs/dynamic-proxy.md) |
| 13 | `visitor-vs-switch` | 访问者 vs switch | sealed + switch 等价性对照 | [docs](docs/visitor-vs-switch.md) |
| 14 | `scoped-values` | — | 虚拟线程、`ScopedValue` | [docs](docs/scoped-values.md) |

横向对照：[`docs/spring-equivalents.md`](docs/spring-equivalents.md) —— 每个模式在 Spring 里对应什么、Spring 省掉了什么、代价是什么。

---

## 三条硬约束

**1. 模块零外部依赖。** 每个场景只依赖 JUnit，不依赖任何其他场景模块。
把 `risk-chain/` 整个目录拷走，依然是一个能 `mvn test` 的完整项目。这是"小项目"定位的兑现方式。

**2. 零 preview 特性。** Java 25 里 Record Patterns 和 Structured Concurrency **仍是 preview**
（JEP 507 第三轮 / JEP 505 第五轮）。开了就得在编译期、surefire、运行期三处都加
`--enable-preview`，产物也不能裸跑。不值得。
sealed、record、switch 模式匹配、虚拟线程、Scoped Values 已经是正式版，够用了。

**3. 不引入 Spring Boot。** Spring 恰好实现了这里近一半的模式 —— 单例（默认 bean scope）、
动态代理（AOP 对接口切面用的就是 `Proxy`）、工厂、门面（构造器注入的 `@Service`）、
中介者（整个 DI 图）、模板方法（`@Transactional`）、观察者（`ApplicationEvent`）、
建造者（`BeanDefinitionBuilder`）。引进来之后每个场景的结论都会退化成"这里用 `@Service` 就行"。
详见 `docs/spring-equivalents.md`。

## 环境

| 组件 | 版本 |
|---|---|
| Java | 25（`--release 25`） |
| Maven | 3.9+ |
| maven-compiler-plugin | 3.16.0 |
| maven-surefire-plugin | 3.6.0（支持 JUnit Platform 6） |
| JUnit | 6.1.3 |

## 约定

- 模块名 / 包名 / 文档名全 ASCII，无中文，无数字前缀
- 每个模块有且只有一个入口类 `demo.<pkg>.Demo`，同时暴露 `main(String[])` 和 `static void run()`
- 场景模块之间**没有**依赖，唯一的跨模块依赖在 `runner`
- `runner` 用 `Class#forName` 按名字加载各 Demo，让它的编译结果和场景进度解耦