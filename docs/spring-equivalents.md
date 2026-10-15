# Spring 对照表

每个模式在 Spring 里对应什么、Spring 省掉了什么、代价是什么。

## 为什么不引入 Spring

本项目讨论过全量引入 Spring Boot，**结论是不引入**。原因：

1. **Spring 恰好替我们实现了这批场景里近一半的模式** —— 一旦引入，每个场景的结论都会退化成"这里用 `@Service` 就行"。
2. **内在矛盾** —— Spring Boot 就是"完整可用应用"的工具箱，而"完整可用应用"正是本项目已否决的方向。
3. **破坏既定约定** —— 模块零外部依赖、零运行时依赖两条都会破。

这张表是折中方案：记录信息，不引入依赖。

## 对照表

| # | 场景 | 设计模式 | Spring 对应物 | Spring 省掉了什么 | 代价 |
|---|---|---|---|---|---|
| 1 | `money-decorator` | 装饰器 | `BeanPostProcessor` / AOP `@Around` | 手写 `PriceRule` 接口 + 链式包装 | AOP 是运行期魔法，调试困难 |
| 2 | `order-builder` | 建造者 | `BeanDefinitionBuilder` / Lombok `@Builder` | 手写 `Builder` 类 | Lombok 是编译期魔法，注解依赖 |
| 3 | `order-state` | 状态 | `@Stateless` / 状态机库（Spring Statemachine） | 手写 `switch` 分派 | Spring Statemachine 是独立库，学习成本高 |
| 4 | `risk-chain` | 责任链 | `Filter` / `HandlerInterceptor` / `OncePerRequestFilter` | 手写链式装配 | 链式顺序由 Spring 控制，不透明 |
| 5 | `pipeline` | 模板方法 | `Template Method` + `@Override` / `InitializingBean` 钩子 | 手写 `templateMethod()` | Spring 的钩子太多，不知道哪个该覆写 |
| 6 | `domain-events` | 观察者 | `ApplicationEvent` + `@EventListener` / Spring Cloud Bus | 手写 `EventBus` + 监听器注册 | 事件顺序不保证，异步事件调试困难 |
| 7 | `payment-gateway` | 策略 + 适配器 + 工厂 | `@ConditionalOnProperty` / `@Primary` / `SmartInitializingSingleton` | 手写 `Factory` + `Adapter` | 策略切换由配置文件控制，不可见 |
| 8 | `checkout-facade` | 门面 + 中介者 | 构造器注入的 `@Service` / `@Component` | 手写 `Facade` + `Mediator` | DI 图就是中介者，但调试困难 |
| 9 | `order-command` | 命令 + 备忘录 | `@Transactional` / `@Async` + 手动 undo | 手写 `Command` + `Memento` | 事务回滚不是命令撤销，语义不同 |
| 10 | `catalog-flyweight` | 享元 | Spring 不管 —— 没有对应的 Bean scope | 手写 `Pool` + `computeIfAbsent` | 无对应物，Spring 不提供 |
| 11 | `singleton-config` | 单例 | Bean 默认 scope 就是 `singleton` | 手写 `Holder` / `DCL` | 隐藏依赖更难发现 —— DI 容器本身就是上帝对象 |
| 12 | `dynamic-proxy` | 代理 | Spring AOP 对基于接口的切面用的就是 `Proxy` + `InvocationHandler` | 手写 `InvocationHandler` | 同一套技术，Spring 封装了一层 |
| 13 | `visitor-vs-switch` | 访问者 vs switch | Spring 不管 —— 没有对应的抽象 | 手写 `Visitor` + `accept()` | 无对应物，Spring 不提供 |
| 14 | `scoped-values` | — | `RequestContextHolder` / `@RequestScope` / `@SessionScope` | 手写 `ScopedValue` + `where().run()` | Spring 的 scope 比 ScopedValue 更复杂 |

## 关键观察

### Spring 省掉的多是"胶水代码"

装饰器、建造者、工厂、门面、单例 —— 这些模式的共同点是**对象创建和组装**。
Spring 的 DI 容器恰好解决了这个问题，所以这些场景用 Spring 写更短。

但**代价是**：DI 容器本身就是一个巨大的中介者 + 单例 + 工厂 + 建造者。
你把模式隐藏了，但没有消除模式的复杂性 —— 只是把它搬到了 Spring 框架里。

### 行为型模式 Spring 帮不了多少

观察者、责任链、命令、访问者 —— 这些模式的核心是**控制流**。
Spring 的 `@EventListener`、`@Async`、`@Transactional` 提供了一些便利，
但控制流的核心逻辑（事件顺序、链式短路、命令撤销、双分派）还是要自己写。

### 高阶模式 Spring 不管

享元、动态代理、访问者 —— 这些模式在 Spring 里**没有直接对应物**。
动态代理有 Spring AOP，但 Spring AOP 用的就是 `java.lang.reflect.Proxy`。
访问者和享元在 Spring 里完全不存在。

### ScopedValue vs Spring Scope

Spring 的 `@RequestScope` / `@SessionScope` 和 `ScopedValue` 解决同一个问题
（跨方法传递上下文），但实现方式完全不同：

- Spring scope：基于 `ThreadLocal` + 请求作用域的生命周期管理
- ScopedValue：基于作用域的不可变绑定 + 自动清理

Spring 的 scope 在虚拟线程上会有内存泄漏问题（因为底层是 ThreadLocal）。
ScopedValue 不会。

## 什么时候应该用 Spring

- **需要完整的应用框架**（Web 容器、数据库连接池、安全框架）
- **团队已经熟悉 Spring**，不需要理解设计模式的底层原理
- **需要 AOP**（事务、缓存、日志）—— Spring AOP 比手写代理方便
- **需要 DI 容器管理对象生命周期** —— 手写单例和工厂是重复劳动

## 什么时候不应该用 Spring

- **不想引入框架依赖**（本项目选择了这条路）
- **需要理解设计模式的底层原理**（本项目的目标）
- **需要零第三方运行时依赖**
- **需要可预测的调试体验**（Spring AOP 的堆栈断裂和动态代理一样）
