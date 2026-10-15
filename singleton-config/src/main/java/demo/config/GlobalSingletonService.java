package demo.config;

/**
 * 服务类 —— <b>为了演示"隐藏依赖"</b>。
 *
 * <p>这个类的构造函数签名<b>完全没有</b>提到配置。调用方看不出它依赖配置。
 * 但只要它跑起来，它就会去摸 {@link StaticConfig#INSTANCE}。
 *
 * <p>看 {@code Demo} 里的对比代码：换成注入式的 {@code InjectedService} 之后，
 * 依赖一眼就能看出来。
 */
public final class GlobalSingletonService {

    private final String name;

    public GlobalSingletonService(String name) {
        this.name = name;
    }

    /** 超时值从全局配置拿。调用方看不到这段依赖。 */
    public int timeoutMs() {
        return StaticConfig.INSTANCE.get(new ConfigKey("timeout.ms"))
                .map(ConfigValue::raw)
                .map(Integer::parseInt)
                .orElse(1000);
    }

    public String describe() {
        return "%s 用全局配置，超时 %dms".formatted(name, timeoutMs());
    }
}