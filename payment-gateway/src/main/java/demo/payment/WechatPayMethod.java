package demo.payment;

import java.math.BigDecimal;

/**
 * 微信支付 —— 一个"天生适配"的策略。
 *
 * <p>它的接口和 {@link PaymentMethod} 几乎一致，所以不需要适配器。
 * 这个类存在的意义是<b>做对照组</b>：不是所有第三方都需要适配，
 * 硬要给每个渠道套一层适配器也是浪费。
 */
public final class WechatPayMethod implements PaymentMethod {

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.WECHAT_PAY;
    }

    @Override
    public PaymentResult pay(PaymentRequest request) {
        // 模拟：金额超过 500 元返回余额不足
        if (request.amount().compareTo(new BigDecimal("500")) > 0) {
            return new PaymentResult.Declined("INSUFFICIENT_BALANCE",
                    "微信零钱余额不足", request.amount());
        }
        return new PaymentResult.Success(
                "WX" + request.idempotencyKey().toUpperCase(), request.amount());
    }
}