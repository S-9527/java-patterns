package demo.money;

/**
 * 优惠券 —— 用模式匹配 switch 代替 {@code instanceof} 链。
 *
 * <p>注意 {@link Coupon} 是 sealed 的，所以这里的 switch <b>不需要 default</b>：
 * 将来往 {@code Coupon} 加第三种实现，这一行会直接编译不过。
 * 这就是 sealed 相比"接口 + instanceof + default"最实在的收益。
 *
 * <p>没达到门槛时不改金额，直接原样返回下游结果 —— 装饰器天然允许"这一层什么都不做"。
 */
public record CouponDiscount(Coupon coupon, PriceRule next) implements PriceRule {

    @Override
    public Money apply(Money amount) {
        var subtotal = next.apply(amount);
        if (!coupon.usableFor(subtotal)) {
            return subtotal;
        }
        // 没有 default：Coupon 是 sealed 的，编译器保证这里穷尽
        return switch (coupon) {
            case Coupon.FlatOff(String code, var minSpend, var off) -> {
                // 减免不超过小计本身，否则会出现负金额
                yield off.amount().compareTo(subtotal.amount()) > 0 ? Money.zero(subtotal.currency()) : subtotal.minus(off);
            }
            case Coupon.PercentOff(String code, var minSpend, int percentOff) ->
                    subtotal.percentageOff(percentOff);
        };
    }

    public String describe() {
        return switch (coupon) {
            case Coupon.FlatOff(String code, var minSpend, var off) ->
                    "%s 满 %s 减 %s".formatted(code, minSpend, off);
            case Coupon.PercentOff(String code, var minSpend, int percentOff) ->
                    "%s 满 %s 打 %d%%".formatted(code, minSpend, percentOff);
        };
    }
}