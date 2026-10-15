package demo.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("价格链 —— 装饰器的行为")
class PriceChainTest {

    private static final Currency CNY = Currency.getInstance("CNY");
    private static final Money ZERO = Money.zero(CNY);

    private static final ItemSubtotal CART = new ItemSubtotal(List.of(
            new LineItem("SKU-1001", 2, Money.of("39.90", CNY)),   // 79.80
            new LineItem("SKU-2044", 1, Money.of("128.00", CNY)))); // 128.00 → 207.80

    @Test
    @DisplayName("每包一层结果变一次，但已有的层不用改")
    void eachLayerChangesResult() {
        assertEquals(Money.of("207.80", CNY), CART.apply(ZERO));

        var member = new MemberDiscount(MemberTier.GOLD, CART);
        assertEquals(Money.of("187.02", CNY), member.apply(ZERO), "10% off");

        var coupon = new CouponDiscount(
                new Coupon.FlatOff("SAVE30", Money.of("150", CNY), Money.of("30", CNY)), member);
        assertEquals(Money.of("157.02", CNY), coupon.apply(ZERO));

        var shipping = new ShippingFee(ShippingZone.OVERSEAS, 80, coupon);
        assertEquals(Money.of("237.02", CNY), shipping.apply(ZERO));

        var tax = new Tax(TaxRate.STANDARD, shipping);
        assertEquals(Money.of("267.83", CNY), tax.apply(ZERO));
    }

    @Test
    @DisplayName("去掉一层就是少包一个 —— 装饰器可以自由增减")
    void layersAreIndividuallyRemovable() {
        var withCoupon = new CouponDiscount(
                new Coupon.FlatOff("SAVE30", Money.of("150", CNY), Money.of("30", CNY)),
                new MemberDiscount(MemberTier.GOLD, CART));
        var withoutCoupon = new MemberDiscount(MemberTier.GOLD, CART);

        assertEquals(withoutCoupon.apply(ZERO).minus(Money.of("30", CNY)), withCoupon.apply(ZERO),
                "两者只差一层 30 元减免");
    }

    @Test
    @DisplayName("⚠ 门槛按折后算：会员折扣可能让券失效。这是业务约定，不是技术细节")
    void couponThresholdIsEvaluatedAfterMemberDiscount() {
        // 小计 207.80，券门槛设 200
        Coupon strict = new Coupon.FlatOff("STRICT200", Money.of("200", CNY), Money.of("30", CNY));
        var chain = new CouponDiscount(strict, new MemberDiscount(MemberTier.GOLD, CART));

        assertEquals(Money.of("187.02", CNY), chain.apply(ZERO),
                "会员 10% 折扣后只剩 187.02 < 200，券被判为不可用 —— 用户会问：我券呢？");

        // 同一个券，把门槛降到 150 就生效了
        var lenient = new CouponDiscount(
                new Coupon.FlatOff("LOOSE150", Money.of("150", CNY), Money.of("30", CNY)),
                new MemberDiscount(MemberTier.GOLD, CART));
        assertEquals(Money.of("157.02", CNY), lenient.apply(ZERO));

        // 教训：装饰器链的套法会改变业务语义。把 CouponDiscount 包在 MemberDiscount 外面
        // 就能让券看到折前金额 —— 这是业务决策，必须显式决定，不能默认。
        var couponOutside = new MemberDiscount(MemberTier.GOLD, new CouponDiscount(strict, CART));
        assertEquals(Money.of("160.02", CNY), couponOutside.apply(ZERO),
                "券包在外面时按 207.80 判定门槛，券生效");
    }

    @Test
    @DisplayName("顺序有影响 —— 同一组装饰器，套法不同结果不同")
    void compositionOrderMatters() {
        // 运费在税基之外：运费也要计税
        var taxOutsideShipping = new Tax(TaxRate.STANDARD,
                new ShippingFee(ShippingZone.OVERSEAS, 80, CART));
        // 运费在税基之内：运费不计税
        var taxInsideShipping = new ShippingFee(ShippingZone.OVERSEAS, 80,
                new Tax(TaxRate.STANDARD, CART));

        assertEquals(Money.of("325.21", CNY), taxOutsideShipping.apply(ZERO),
                "税在运费外：先收运费再对 287.80 计税");
        assertEquals(Money.of("314.81", CNY), taxInsideShipping.apply(ZERO),
                "税在运费内：先对 207.80 计税再加运费");
        assertEquals(Money.of("10.40", CNY),
                taxOutsideShipping.apply(ZERO).minus(taxInsideShipping.apply(ZERO)),
                "差额正好是运费被计的那 13% 税：80 × 0.13");
    }

    @Test
    @DisplayName("满额包邮：门槛按折扣后的金额判断")
    void freeShippingOverThreshold() {
        // 小计 207.80 已满 99，境内包邮
        var domestic = new ShippingFee(ShippingZone.DOMESTIC, 12, CART);
        assertEquals(Money.of("207.80", CNY), domestic.apply(ZERO));

        // 换个贵一点的场景：门槛 150，折扣后跌破 150 就要收运费
        var small = new ItemSubtotal(List.of(new LineItem("X", 1, Money.of("120", CNY))));
        var domestic2 = new ShippingFee(ShippingZone.HK_MACAU, 20, small);
        assertEquals(Money.of("140.00", CNY), domestic2.apply(ZERO), "120 未满 150，加 20 运费");
    }

    @Test
    @DisplayName("优惠券没达到门槛时，这一层什么都不做")
    void couponBelowThresholdIsNoOp() {
        var small = new ItemSubtotal(List.of(new LineItem("X", 1, Money.of("10", CNY))));
        var coupon = new CouponDiscount(
                new Coupon.FlatOff("SAVE30", Money.of("200", CNY), Money.of("30", CNY)), small);
        assertEquals(Money.of("10.00", CNY), coupon.apply(ZERO));
    }

    @Test
    @DisplayName("减免额不会把金额打成负数")
    void flatOffNeverGoesNegative() {
        var small = new ItemSubtotal(List.of(new LineItem("X", 1, Money.of("20", CNY))));
        var coupon = new CouponDiscount(
                new Coupon.FlatOff("HUGE", Money.of("10", CNY), Money.of("100", CNY)), small);
        assertEquals(Money.zero(CNY), coupon.apply(ZERO), "减 100 但只有 20，结果夹到 0");
    }

    @Test
    @DisplayName("百分比券走另一条分支 —— sealed switch 穷尽了两条路")
    void percentOffBranch() {
        var coupon = new CouponDiscount(
                new Coupon.PercentOff("HALF", Money.of("100", CNY), 50), CART);
        assertEquals(Money.of("103.90", CNY), coupon.apply(ZERO), "207.80 打五折");
    }

    @Test
    @DisplayName("空购物车走完整条链也不炸")
    void emptyCartSurvivesWholeChain() {
        PriceRule chain = new Tax(TaxRate.STANDARD,
                new ShippingFee(ShippingZone.DOMESTIC, 12,
                        new MemberDiscount(MemberTier.GOLD, new ItemSubtotal(List.of()))));
        assertEquals(Money.of("0.00", CNY), chain.apply(ZERO));
    }
}