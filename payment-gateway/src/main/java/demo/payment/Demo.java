package demo.payment;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略 + 适配器 + 工厂。
 *
 * <p>一次支付请求穿过：调用方 → 工厂 → 策略（可能带适配器）→ 结果。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        heading("四个渠道，同一个接口");
        var factory = new PaymentMethodFactory("2021000100000001");
        for (var channel : PaymentChannel.values()) {
            var method = factory.create(channel).orElseThrow();
            System.out.printf("  %-12s → %s%n", channel.label(), method.getClass().getSimpleName());
        }

        heading("同一笔 268 元的订单，换渠道结果不同");
        var request = new PaymentRequest("SO-2001", new BigDecimal("268.00"),
                "user@bank", "IDEM-001");
        for (var channel : List.of(PaymentChannel.ALIPAY, PaymentChannel.WECHAT_PAY,
                PaymentChannel.BANK_CARD, PaymentChannel.BALANCE)) {
            var result = factory.create(channel).orElseThrow().pay(request);
            System.out.printf("  %-12s %s%n", channel.label(), describe(result));
        }

        heading("支付宝大额：SDK 抛异常，适配器翻译成 Errored");
        var bigOrder = new PaymentRequest("SO-2002", new BigDecimal("5000.00"),
                "user@bank", "IDEM-002");
        var alipayResult = factory.create(PaymentChannel.ALIPAY).orElseThrow().pay(bigOrder);
        System.out.println("  %s".formatted(describe(alipayResult)));
        System.out.println("  该不该重试：%s".formatted(alipayResult.isRetryable()
                ? "是 —— 网关超时，换个时间或直接重试" : "否"));

        heading("⚠ 适配器最容易出错的地方：金额单位");
        demoUnitConversion();

        heading("哪些渠道需要适配器");
        System.out.println("  支付宝 → 需要 AlipayAdapter：参数打包成 SDK 请求对象、金额是字符串分、失败抛受检异常");
        System.out.println("  微信   → 不需要 WechatPayMethod：接口本来就和我们一致");
        System.out.println();
        System.out.println("  不是所有第三方都值得套一层适配器。接口一致时直接实现策略更省。");

        heading("重试判定的三种结果");
        demoRetryMatrix();

        heading("不支持的渠道");
        var empty = new PaymentMethodFactory("APP-ID").create(null);
        System.out.println("  create(null) → %s".formatted(empty.isPresent() ? "有" : "Optional.empty"));
        System.out.println("  返回 Optional 而不是抛异常 —— 调用方想「提示用户换个方式」，不是让流程炸掉");

        System.out.println();
    }

    private static void demoUnitConversion() {
        var samples = List.of("0.01", "1.00", "268.00", "99.99", "1234.56");
        System.out.println("  %-10s %-10s %s".formatted("元", "传给SDK(分)", "SDK返回转回(元)"));
        for (var s : samples) {
            var yuan = new BigDecimal(s);
            var fen = AlipayAdapter.yuanToFen(yuan);
            var back = AlipayAdapter.fenToYuan(fen);
            var ok = back.compareTo(yuan) == 0 ? "✓" : "✗ 精度丢失!";
            System.out.printf("  %-10s %-10s %-12s %s%n", yuan, fen, back, ok);
        }
        System.out.println();
        System.out.println("  如果适配器忘了做这个转换：用户充 268 元，支付宝实际只收到 2.68 元。");
        System.out.println("  而所有代码看起来都是对的 —— 不会有编译错误，单元测试也未必覆盖到。");
    }

    private static void demoRetryMatrix() {
        var samples = List.of(
                new PaymentResult.Success("TX-1", new BigDecimal("10")),
                new PaymentResult.Declined("INSUFFICIENT_BALANCE", "余额不足", new BigDecimal("10")),
                new PaymentResult.Errored("GATEWAY_TIMEOUT", true, new BigDecimal("10")),
                new PaymentResult.Errored("SIGN_INVALID", false, new BigDecimal("10")));

        for (var r : samples) {
            System.out.printf("  %-58s %s%n", describe(r),
                    r.isRetryable() ? "该重试" : "不该重试");
        }
        System.out.println();
        System.out.println("  成功的不该重试 —— 幂等键保护下重试也安全，但没必要。");
    }

    /** 用 sealed 穷尽 switch 描述结果，不写 default。 */
    private static String describe(PaymentResult result) {
        return switch (result) {
            case PaymentResult.Success(String txId, var amount) ->
                    "成功 流水号 %s 金额 %s".formatted(txId, amount);
            case PaymentResult.Declined(String code, String msg, var amount) ->
                    "拒绝 [%s] %s（%s）".formatted(code, msg, amount);
            case PaymentResult.Errored(String code, boolean retryable, var amount) ->
                    "错误 [%s] %s%s".formatted(code, amount, retryable ? " 可重试" : " 不可重试");
        };
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }
}