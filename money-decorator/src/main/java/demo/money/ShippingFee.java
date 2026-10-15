package demo.money;

import java.math.BigDecimal;

/**
 * 运费。和折扣相反，它是<b>加法</b>而不是乘法 —— 但装饰器的形状完全一样：
 * 持有 next，先要下游结果，再叠加自己这一层。
 *
 * <p>这正是装饰器和策略的分界：装饰器包的是"同一个类型的对象"（{@link PriceRule}），
 * 策略包的是"可替换的算法"。这里每一层做的是不同算术，但如果它们都叫 {@code apply(Money)}，
 * 就说明它们在结构上可以互相替换。
 */
public record ShippingFee(ShippingZone zone, int flatFee, PriceRule next) implements PriceRule {

    public ShippingFee {
        if (flatFee < 0) {
            throw new IllegalArgumentException("运费不能为负");
        }
    }

    public static ShippingFee flat(ShippingZone zone, int fee) {
        return new ShippingFee(zone, fee, null);
    }

    @Override
    public Money apply(Money amount) {
        var subtotal = next.apply(amount);

        // 空购物车不收运费。
        // 写这个分支是被测试逼出来的：没有它，空车会白挨一份运费和税。
        if (subtotal.isZero()) {
            return subtotal;
        }

        // 满额包邮：门槛按"折扣后"的金额判断 —— 这是业务决策，不是技术细节。
        // 换一种约定只需要改这一行，链上其他层不用动。
        if (zone.hasFreeShipping()
                && subtotal.amount().compareTo(BigDecimal.valueOf(zone.freeThreshold())) >= 0) {
            return subtotal;
        }
        return subtotal.plus(new Money(BigDecimal.valueOf(flatFee), subtotal.currency()));
    }

    public String describe() {
        return zone.hasFreeShipping()
                ? "%s 运费 %d（满 %d 包邮）".formatted(zone.label(), flatFee, zone.freeThreshold())
                : "%s 运费 %d".formatted(zone.label(), flatFee);
    }
}