package demo.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 配置接口 —— <b>反面教材之外的那条路</b>。
 *
 * <p>它看起来和单例没有区别（同样有 get/set）。区别在<b>调用方式</b>：
 * <ul>
 *   <li>单例：{@code StaticConfig.INSTANCE.get(key)} —— 上帝对象，谁都拿得到</li>
 *   <li>注入：{@code service.get(serviceAuthToken)} —— 依赖显式，换一个实例就是换个对象</li>
 * </ul>
 */
public interface ConfigSource {

    Set<ConfigKey> keys();

    Optional<ConfigValue> get(ConfigKey key);

    void set(ConfigKey key, ConfigValue value);
}