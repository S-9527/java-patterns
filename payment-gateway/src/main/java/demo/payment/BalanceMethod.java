package demo.payment;

import java.math.BigDecimal;

/** 站内余额 —— 最简单的策略，永远成功。 */
public final class BalanceMethod implements PaymentMethod {

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.BALANCE;
    }

    @Override
    public PaymentResult pay(PaymentRequest request) {
        return new PaymentResult.Success("BAL-" + request.idempotencyKey(), request.amount());
    }
}