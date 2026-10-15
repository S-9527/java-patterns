package demo.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 适配器：把支付宝 SDK 的接口翻译成我们统一的 {@link PaymentMethod}。
 *
 * <p>它要干的活有三件，每件都对应一处真实世界的差异：
 *
 * <ol>
 *   <li><b>参数翻译</b> —— 我们的 record 变 SDK 特有的请求对象，字段名全不一样</li>
 *   <li><b>返回翻译</b> —— SDK 的字符串金额（单位分）变我们的 {@link BigDecimal}（单位元）</li>
 *   <li><b>异常翻译</b> —— SDK 抛受检异常，变成我们返回 {@link PaymentResult.Errored}</li>
 * </ol>
 *
 * <p>第二件是最危险的：{@link #yuanToFen} 和 {@link #fenToYuan} 必须成对且精确。
 * 漏掉任何一处，用户充 100 元会被扣 1 元，而所有代码看起来都是对的。
 * {@code AlipayAdapterTest} 专门锁住了这一对转换。
 */
public final class AlipayAdapter implements PaymentMethod {

    private final String appId;

    public AlipayAdapter(String appId) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        this.appId = appId;
    }

    @Override
    public PaymentChannel channel() {
        return PaymentChannel.ALIPAY;
    }

    @Override
    public PaymentResult pay(PaymentRequest request) {
        // ---- 1. 参数翻译 ----
        var sdkRequest = new AlipaySdk.AlipayTradeRequest(
                request.orderId(),
                yuanToFen(request.amount()),
                "订单 " + request.orderId(),
                "https://merchant.example.com/notify/" + request.idempotencyKey());

        // ---- 2. 异常翻译：SDK 抛，我们返回 ----
        AlipaySdk.AlipayTradeResponse response;
        try {
            response = AlipaySdk.tradePay(sdkRequest);
        } catch (AlipaySdk.AlipayException e) {
            // 超时类错误可重试，其他不可重试 —— 和 domain-events 场景同一套判断
            boolean retryable = e.getMessage() != null
                    && e.getMessage().contains("TIMEOUT");
            return new PaymentResult.Errored(normalizeCode(e.getMessage()), retryable,
                    request.amount());
        }

        // ---- 3. 返回翻译：单位从分变元 ----
        if (!"TRADE_SUCCESS".equals(response.status())) {
            return new PaymentResult.Declined(response.status(),
                    "支付宝返回状态 " + response.status(), request.amount());
        }
        return new PaymentResult.Success(response.tradeNo(), fenToYuan(response.totalAmount()));
    }

    /**
     * 把 SDK 的原始异常消息规整成错误码。
     *
     * <p>不做这层转换的话错误码会长成 {@code ALIPAY_ALIPAY_GATEWAY_TIMEOUT} ——
     * 前缀重复，看着别扭。
     */
    private static String normalizeCode(String raw) {
        return raw == null || raw.isBlank() ? "ALIPAY_UNKNOWN" : "ALIPAY_" + raw;
    }

    /** 元 → 分。SDK 用字符串传金额，单位是分。 */
    static String yuanToFen(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** 分 → 元。用 {@code new BigDecimal(String)} 而不是 {@code double}，见 money-decorator。 */
    static BigDecimal fenToYuan(String fen) {
        return new BigDecimal(fen).movePointLeft(2);
    }
}