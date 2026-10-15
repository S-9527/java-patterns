package demo.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("适配器 —— 把第三方 SDK 翻译成统一接口")
class AdapterTest {

    private static final AlipayAdapter ADAPTER = new AlipayAdapter("APP-ID");

    private static PaymentRequest request(String amount) {
        return new PaymentRequest("SO-1", new BigDecimal(amount), "user@bank", "IDEM-1");
    }

    @ParameterizedTest(name = "{0} 元 → {1} 分")
    @CsvSource({
            "0.01,   1",
            "1.00,   100",
            "99.99,  9999",
            "268.00, 26800",
            "1234.56, 123456"
    })
    @DisplayName("元 → 分")
    void yuanToFen(String yuan, String expectedFen) {
        assertEquals(expectedFen, AlipayAdapter.yuanToFen(new BigDecimal(yuan)));
    }

    @ParameterizedTest(name = "{0} 分 → {1} 元")
    @CsvSource({
            "1,      0.01",
            "100,    1.00",
            "9999,   99.99",
            "26800,  268.00",
            "123456, 1234.56"
    })
    @DisplayName("分 → 元")
    void fenToYuan(String fen, String expectedYuan) {
        assertEquals(new BigDecimal(expectedYuan), AlipayAdapter.fenToYuan(fen));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1.00", "99.99", "268.00", "1234.56", "0.07", "1234567.89"})
    @DisplayName("⚠ 往返转换必须无损 —— 漏掉单位转换就会 268 元变 2.68 元")
    void roundTripIsLossless(String yuan) {
        var original = new BigDecimal(yuan);
        var fen = AlipayAdapter.yuanToFen(original);
        var back = AlipayAdapter.fenToYuan(fen);

        assertEquals(0, back.compareTo(original),
                "%s 元 → %s 分 → %s 元，精度丢失了".formatted(yuan, fen, back));
    }

    @Test
    @DisplayName("用 BigDecimal 而不是 double —— 0.01 元不能变成 0 分")
    void usesBigDecimalNotDouble() {
        // 如果 yuanToFen 用 double 算，0.01 * 100 = 0.9999999999999999，setScale 后变成 1，
        // 但 1.15 * 100 = 114.99999999999999，会被截成 114 —— 少收一分钱
        assertEquals("115", AlipayAdapter.yuanToFen(new BigDecimal("1.15")));

        double naive = 1.15 * 100;
        assertFalse((long) naive == 115, "double 路径确实会算错：" + naive);
    }

    @Test
    @DisplayName("正常支付：SDK 异常被翻译成 Success")
    void successfulPayment() {
        var result = ADAPTER.pay(request("268.00"));

        var success = assertInstanceOf(PaymentResult.Success.class, result);
        assertEquals(0, success.amount().compareTo(new BigDecimal("268.00")));
        assertTrue(success.transactionId().startsWith("2026"));
    }

    @Test
    @DisplayName("⚠ SDK 抛异常 → 翻译成 Errored，而不是往外抛")
    void sdkExceptionBecomesResult() {
        var result = ADAPTER.pay(request("5000.00"));

        var errored = assertInstanceOf(PaymentResult.Errored.class, result,
                "SDK 抛的异常必须被翻译成返回值，否则调用方得写 try-catch");
        assertTrue(errored.errorCode().contains("TIMEOUT"));
        assertTrue(errored.isRetryable(), "网关超时该重试");
    }

    @Test
    @DisplayName("翻译后调用方不需要 try-catch")
    void callersNeedNoTryCatch() throws NoSuchMethodException {
        // 这个"测试"本身就是文档：pay 不声明任何异常
        var methodSignature = PaymentMethod.class.getMethod("pay", PaymentRequest.class);
        assertEquals(0, methodSignature.getExceptionTypes().length,
                "pay() 不该声明异常 —— 异常翻译是适配器的职责");
        assertEquals(0, PaymentMethodFactory.class.getMethod("create", PaymentChannel.class)
                        .getExceptionTypes().length,
                "create() 也不该声明异常");
    }

    @Test
    @DisplayName("同一笔金额，四个渠道结果不同但接口一致")
    void sameRequestDifferentChannels() {
        var req = request("268.00");
        var factory = new PaymentMethodFactory("APP-ID");

        for (var channel : PaymentChannel.values()) {
            var result = factory.create(channel).orElseThrow().pay(req);
            assertInstanceOf(PaymentResult.class, result);
            assertEquals(0, result.amount().compareTo(new BigDecimal("268.00")),
                    "%s 返回的金额必须和请求一致".formatted(channel));
        }
    }

    @Test
    @DisplayName("业务拒绝和系统错误是两回事")
    void declinedVsErrored() {
        // 微信：余额不足 → Declined，不该重试
        var declined = new WechatPayMethod().pay(request("600.00"));
        assertInstanceOf(PaymentResult.Declined.class, declined);
        assertFalse(declined.isRetryable(), "余额不足重试一万次也没用");

        // 支付宝：网关超时 → Errored，可重试
        var errored = ADAPTER.pay(request("5000.00"));
        assertInstanceOf(PaymentResult.Errored.class, errored);
        assertTrue(errored.isRetryable());
    }

    @Test
    @DisplayName("重试判定用 sealed 穷尽 switch，不写 default")
    void retryableIsExhaustive() {
        var samples = java.util.List.of(
                new PaymentResult.Success("TX", new BigDecimal("1")),
                new PaymentResult.Declined("X", "y", new BigDecimal("1")),
                new PaymentResult.Errored("E", true, new BigDecimal("1")),
                new PaymentResult.Errored("E", false, new BigDecimal("1")));

        assertFalse(samples.get(0).isRetryable(), "成功不该重试");
        assertFalse(samples.get(1).isRetryable(), "业务拒绝不该重试");
        assertTrue(samples.get(2).isRetryable());
        assertFalse(samples.get(3).isRetryable());
    }

    @Test
    @DisplayName("成功结果必须有流水号")
    void successRequiresTransactionId() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PaymentResult.Success(" ", new BigDecimal("1"))) != null);
    }

    @Test
    @DisplayName("适配器需要配置才能创建")
    void adapterNeedsAppId() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AlipayAdapter(""));
    }

    @Test
    @DisplayName("BankCardMethod 要求付款账号绑定过卡")
    void bankCardRequiresBinding() {
        var method = new BankCardMethod();

        var unbound = method.pay(new PaymentRequest("SO-1", new BigDecimal("10"), "user@bank", "K"));
        assertInstanceOf(PaymentResult.Declined.class, unbound);

        var bound = method.pay(new PaymentRequest("SO-1", new BigDecimal("10"), "91234", "K"));
        assertInstanceOf(PaymentResult.Success.class, bound);
    }
}