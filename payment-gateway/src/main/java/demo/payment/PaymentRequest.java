package demo.payment;

import java.math.BigDecimal;

/**
 * 支付请求。
 *
 * <p>用 record 而不是一堆构造参数：第三方 SDK 的接口千奇百怪，
 * 但对外我们的请求结构必须<b>稳定且统一</b>。适配器的价值就在这里。
 */
public record PaymentRequest(String orderId, BigDecimal amount,
                             String payerAccount, String idempotencyKey) {

    public PaymentRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("金额必须为正：" + amount);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("幂等键不能为空 —— 支付请求必须能安全重试");
        }
    }
}