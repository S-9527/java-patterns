package demo.payment;

import java.math.BigDecimal;

/** 银行卡 —— 需要卡号和 CVV，所以多带两个字段。 */
public final class BankCardMethod implements PaymentMethod {

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.BANK_CARD;
    }

    @Override
    public PaymentResult pay(PaymentRequest request) {
        // 模拟：必须以 9 开头的 payerAccount 才能用卡
        if (!request.payerAccount().startsWith("9")) {
            return new PaymentResult.Declined("CARD_NOT_BOUND",
                    "该付款账号未绑定银行卡", request.amount());
        }
        return new PaymentResult.Success("CARD-" + request.idempotencyKey(), request.amount());
    }
}