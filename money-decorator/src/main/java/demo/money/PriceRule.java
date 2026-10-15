package demo.money;

/**
 * 价格链上的一环。
 *
 * <p>装饰器模式的核心就是这个接口：所有规则都长一个样，于是规则可以互相包裹。
 * {@link ItemSubtotal} 是链的起点（不使用传入值，从商品列表算小计），
 * 其余每条规则都持有一个 {@code next}，把活先交给下游，再对结果做加工 —— 这就是"洋葱"。
 *
 * <p>sealed 的作用不是"少写一个 implements"，而是<b>让调用方知道链上到底有哪些规则</b>：
 * 编译器能检查 {@code switch} 是否穷尽，新增一种规则时所有需要处理它的地方都会报错。
 */
public sealed interface PriceRule permits ItemSubtotal, MemberDiscount, CouponDiscount, ShippingFee, Tax {

    /** 返回传入金额经过本规则调整后的结果。 */
    Money apply(Money amount);
}