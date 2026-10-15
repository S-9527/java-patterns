package demo.payment;

/**
 * 支付方式 —— <b>策略模式</b>的接口。
 *
 * <p>策略和装饰器的分界：
 * <ul>
 *   <li>装饰器包装的是<b>同一个类型的对象</b>（{@code PriceRule} 包 {@code PriceRule}），
 *       它们可以层层嵌套，每层做不同的事</li>
 *   <li>策略是<b>换一个</b>：这些实现是平行的、互斥的，一个请求只走其中一条</li>
 * </ul>
 *
 * <p>对比 {@code money-decorator} 场景：那里的 {@code MemberDiscount} 和
 * {@code ShippingFee} 能串成一条链；这里的 {@code AlipayPayment} 和
 * {@code WechatPayment} 永远不会同时出现。
 */
public interface PaymentMethod {

    PaymentChannel channel();

    /**
     * 执行支付。
     *
     * <p>不声明受检异常 —— 第三方 SDK 的异常都被各自的适配器翻译成
     * {@link PaymentResult.Errored} 了。这是适配器的一个核心职责：
     * <b>把"抛异常的语言"翻译成"返回值的语言"</b>。
     */
    PaymentResult pay(PaymentRequest request);
}