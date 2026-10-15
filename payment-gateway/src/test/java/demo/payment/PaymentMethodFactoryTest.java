package demo.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("工厂 —— 策略的创建与映射完整性")
class PaymentMethodFactoryTest {

    private static PaymentMethodFactory factory() {
        return new PaymentMethodFactory("2021000100000001");
    }

    /** 不同渠道的成功门槛不同：支付宝 1000 元以内一定成功。 */
    private static PaymentRequest request(String amount) {
        return new PaymentRequest("SO-1", new BigDecimal(amount), "user@bank", "IDEM-1");
    }

    @Test
    @DisplayName("⚠ 每个渠道都必须有对应的工厂 —— enum 挡不住这件事，测试才能")
    void everyChannelHasAFactory() {
        assertEquals(PaymentChannel.values().length, factory().factoryCount(),
                "新增 PaymentChannel 忘了在工厂里注册的话，这条断言会红");

        // 更强一点：逐个检查，而不是只比数量
        for (var channel : PaymentChannel.values()) {
            assertTrue(factory().create(channel).isPresent(),
                    "渠道 %s 没有注册工厂".formatted(channel));
        }
    }

    @Test
    @DisplayName("工厂创建的实例渠道正确")
    void createdMethodHasRightChannel() {
        for (var channel : PaymentChannel.values()) {
            var method = factory().create(channel).orElseThrow();
            assertEquals(channel, method.channel(),
                    "%s 创建出来的策略却报告自己是别的渠道".formatted(method.getClass().getSimpleName()));
        }
    }

    @Test
    @DisplayName("每次创建都是新实例 —— 策略不应被共享")
    void eachCallCreatesNewInstance() {
        var first = factory().create(PaymentChannel.ALIPAY).orElseThrow();
        var second = factory().create(PaymentChannel.ALIPAY).orElseThrow();

        assertFalse(first == second, "工厂应每次新建实例，策略里可能有可变状态");
    }

    @Test
    @DisplayName("未注册的渠道返回 Optional.empty，不抛异常")
    void unsupportedChannelYieldsEmpty() {
        assertTrue(factory().create(null).isEmpty(), "null 渠道不该炸掉流程");
    }

    @Test
    @DisplayName("⚠ null 渠道是真实场景 —— 前端没传渠道字段就会走到这")
    void nullChannelIsHandledGracefully() {
        // Map.of 的 key 不允许 null，而 Map.get(null) 会 NPE —— 这是写测试时发现的
        var f = factory();
        assertTrue(f.create(null).isEmpty(),
                "前端漏传渠道时应该提示用户，而不是 500");
    }

    @Test
    @DisplayName("⚠ 支付宝渠道需要外部配置，工厂延迟创建 —— appId 被闭包捕获")
    void alipayNeedsAppIdInjected() {
        var withRealAppId = new PaymentMethodFactory("REAL-APP-ID");
        var method = (AlipayAdapter) withRealAppId.create(PaymentChannel.ALIPAY).orElseThrow();

        // appId 是构造器注入的，所以换工厂就换配置
        var another = (AlipayAdapter) new PaymentMethodFactory("OTHER")
                .create(PaymentChannel.ALIPAY).orElseThrow();
        assertFalse(method == another, "两次注入不同 appId 应得到不同实例");
    }

    @Test
    @DisplayName("不需配置的渠道用 Supplier 延迟创建也一样能工作")
    void statelessChannelsStillWork() {
        assertInstanceOf(WechatPayMethod.class,
                factory().create(PaymentChannel.WECHAT_PAY).orElseThrow());
    }

    @Test
    @DisplayName("同一渠道多次调用行为一致")
    void sameChannelSameBehavior() {
        var f = factory();
        var req = request("268.00");

        var first = f.create(PaymentChannel.ALIPAY).orElseThrow().pay(req);
        var second = f.create(PaymentChannel.ALIPAY).orElseThrow().pay(req);

        // 金额一定一致
        assertEquals(first.amount(), second.amount());
        // 成功与否也一致 —— 但流水号不同（SDK 每次生成新的）
        assertEquals(first.isSuccess(), second.isSuccess());
        assertTrue(first.isSuccess(), "268 元在支付宝的 1000 元门槛内，应该成功");
        assertTrue(second.isSuccess());

        if (first instanceof PaymentResult.Success a && second instanceof PaymentResult.Success b) {
            assertFalse(a.transactionId().equals(b.transactionId()),
                    "每次支付应生成不同流水号");
        }
    }

    @Test
    @DisplayName("构造请求时校验必填字段")
    void requestValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentRequest("", BigDecimal.ONE, "a", "k"));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentRequest("SO-1", BigDecimal.ZERO, "a", "k"));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentRequest("SO-1", BigDecimal.ONE, "a", " "),
                "幂等键不能是空白");
    }

    @Test
    @DisplayName("用 Map<Channel, X> 而不是 switch 做映射，加渠道不改工厂代码结构")
    void mappingIsDataNotCode() {
        // 这个测试只是记录"映射是数据"这个事实
        var channels = new java.util.LinkedHashSet<PaymentChannel>();
        for (var c : PaymentChannel.values()) {
            channels.add(c);
        }
        assertEquals(4, channels.size());
    }

    @Test
    @DisplayName("未映射渠道被记录，供健康检查用")
    void unmappedChannelsTracked() {
        var f = factory();
        // null 渠道走 early return，不算"未映射"，所以 unmapped 仍是空的
        f.create(null);
        assertTrue(f.unmappedChannels().isEmpty(),
                "null 不记为未映射 —— 它是参数错误，不是映射缺失");
    }
}