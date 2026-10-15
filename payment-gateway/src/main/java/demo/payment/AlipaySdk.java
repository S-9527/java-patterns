package demo.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 一个假装的第三方 SDK：支付宝。
 *
 * <p><b>它的接口和我们的 {@link PaymentMethod} 长得完全不一样</b>：
 * <ul>
 *   <li>参数打包成一个 SDK 特有的请求对象，字段名是 out_trade_no / total_amount 这种</li>
 *   <li>返回 SDK 特有的响应对象，金额是<b>字符串</b>（支付宝的金额单位是"分"）</li>
 *   <li>失败时抛 SDK 自己的受检异常，而不是返回值</li>
 * </ul>
 *
 * <p>这三处差异都是真实世界会遇到的样子。{@link AlipayAdapter} 的全部价值
 * 就是把它们翻译掉。
 *
 * <p>金额单位是"分"这一点尤其真实 —— 如果适配器忘了转换，
 * 用户充 100 元会被扣 1 元，而所有代码都"看起来是对的"。
 */
final class AlipaySdk {

    private AlipaySdk() {
    }

    /** SDK 特有的请求对象。 */
    record AlipayTradeRequest(String outTradeNo, String totalAmount,
                              String subject, String notifyUrl) {
    }

    /** SDK 特有的响应对象。金额是字符串，单位是分。 */
    record AlipayTradeResponse(String tradeNo, String totalAmount, String status) {
    }

    /** SDK 抛的异常。 */
    static class AlipayException extends Exception {
        AlipayException(String message) {
            super(message);
        }
    }

    /**
     * ⚠ 注意这个方法的入参单位是<b>分</b>。
     *
     * <p>所以"超过 1000 元"的判断必须先转回元：{@code amount.divide(100)}。
     * 这里写成 {@code compareTo(new BigDecimal("100000"))}，和 {@code AlipayAdapter} 里
     * {@code 1000 * 100} 对得上 —— 单位错了不会有编译错误，只会让判断永远不触发。
     *
     * <p>这是我写这个假 SDK 时真实踩到的一次：先写成和 {@code AlipayAdapter}
     * 一样的 {@code new BigDecimal("1000")}，结果 268 元（= 26800 分）
     * 被判定成"超过 1000 元"，所有支付宝支付都超时了。
     */
    static AlipayTradeResponse tradePay(AlipayTradeRequest req) throws AlipayException {
        if (req.totalAmount() == null || req.totalAmount().isBlank()) {
            throw new AlipayException("total_amount 不能为空");
        }
        // totalAmount 单位是分，100000 分 = 1000 元
        var amountFen = new BigDecimal(req.totalAmount());
        if (amountFen.compareTo(new BigDecimal("100000")) > 0) {
            throw new AlipayException("ALIPAY_GATEWAY_TIMEOUT");
        }
        return new AlipayTradeResponse(
                "2026" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                req.totalAmount(),
                "TRADE_SUCCESS");
    }
}