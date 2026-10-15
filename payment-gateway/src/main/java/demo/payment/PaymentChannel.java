package demo.payment;

/**
 * 支付渠道 —— 决定"有哪些支付方式"。
 *
 * <p>enum 而非 sealed：渠道是<b>开放的</b>，新增一个支付渠道不该要求改动
 * {@code PaymentMethodFactory} 的编译期结构（虽然有测试锁住映射完整性）。
 * 这和 {@code order-state} 里的订单状态不同 —— 状态集合是封闭且已知的。
 *
 * <p>这个对照是本场景的一个重点：<b>什么时候用 enum，什么时候用 sealed</b>。
 */
public enum PaymentChannel {

    ALIPAY("支付宝"),
    WECHAT_PAY("微信支付"),
    BANK_CARD("银行卡"),
    BALANCE("余额");

    private final String label;

    PaymentChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}