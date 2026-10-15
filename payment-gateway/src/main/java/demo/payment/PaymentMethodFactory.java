package demo.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工厂：按渠道创建支付方式。
 *
 * <p>为什么需要工厂？如果调用方自己写 {@code switch}：
 *
 * <pre>{@code
 * PaymentMethod m = switch (channel) {
 *     case ALIPAY -> new AlipayAdapter(APP_ID);
 *     case WECHAT_PAY -> new WechatPayMethod();
 *     ...
 * };
 * }</pre>
 *
 * <p>那"APP_ID 从哪来"这个知识就散落到每个调用点了。工厂把它收在一处。
 *
 * <p><b>这个工厂用了 enum 的全部值，但用测试锁住完整性</b> ——
 * {@code PaymentMethodFactoryTest.everyChannelHasAFactory} 断言
 * {@code values().length == factories.size()}。新增渠道忘了注册，测试就红。
 *
 * <p>这是 enum + 工厂的经典权衡：enum 保证字段存在，测试保证映射存在，
 * 但编译器<b>不能</b>保证映射正确。这就是为什么 {@code PaymentChannel}
 * 用 enum 而不是 sealed —— 渠道是开放的，不该在编译期锁死。
 */
public final class PaymentMethodFactory {

    private final Map<PaymentChannel, java.util.function.Supplier<PaymentMethod>> factories;
    private final List<PaymentChannel> unmapped = new java.util.ArrayList<>();

    public PaymentMethodFactory(String appId) {
        this.factories = Map.of(
                // 需要外部配置的渠道用 lambda 延迟创建
                PaymentChannel.ALIPAY, () -> new AlipayAdapter(appId),
                // 无状态的可以直接放实例
                PaymentChannel.WECHAT_PAY, WechatPayMethod::new,
                PaymentChannel.BANK_CARD, BankCardMethod::new,
                PaymentChannel.BALANCE, BalanceMethod::new);
    }

    /**
     * 创建支付方式。
     *
     * <p>返回 {@link Optional} 而不是抛异常 —— 调用方通常想"不支持这个渠道就提示用户换个方式"，
     * 而不是让整个流程炸掉。
     */
    public Optional<PaymentMethod> create(PaymentChannel channel) {
        // ⚠ Map.of 不允许 null key，而 Map.get(null) 会走 hashCode 然后 NPE。
        // 调用方完全可能拿到一个 null 渠道（比如前端没传渠道字段），
        // 那时应该返回 empty 而不是炸掉。
        if (channel == null) {
            return Optional.empty();
        }
        var supplier = factories.get(channel);
        if (supplier == null) {
            unmapped.add(channel);
            return Optional.empty();
        }
        return Optional.of(supplier.get());
    }

    /** 已知不支持的渠道。正常情况下应该是空的。 */
    public List<PaymentChannel> unmappedChannels() {
        return List.copyOf(unmapped);
    }

    public int factoryCount() {
        return factories.size();
    }
}