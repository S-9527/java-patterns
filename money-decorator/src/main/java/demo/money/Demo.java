package demo.money;

import java.util.Currency;
import java.util.List;

/**
 * 装饰器：逐层包裹，看清"洋葱"。
 *
 * <p>装饰器的关键在于<b>每包一层，结果就变一次，但已有的层一行都不用改</b>。
 * 这个 Demo 把洋葱剥开给你看：一层层往上包，每包一次就把金额打出来。
 */
public final class Demo {

    private static final Currency CNY = Currency.getInstance("CNY");

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var items = List.of(
                new LineItem("SKU-1001", 2, Money.of("39.90", CNY)),
                new LineItem("SKU-2044", 1, Money.of("128.00", CNY)),
                new LineItem("SKU-3310", 3, Money.of("15.50", CNY)));

        heading("购物车");
        items.forEach(i -> out("  %-10s × %d  单价 %-12s 小计 %s"
                .formatted(i.sku(), i.quantity(), i.unitPrice(), i.lineTotal())));

        var zero = Money.zero(CNY);
        var subtotal = new ItemSubtotal(items);

        heading("逐层包裹 —— 洋葱");
        out("  %-24s %s".formatted("商品小计", subtotal.apply(zero)));

        var member = new MemberDiscount(MemberTier.GOLD, subtotal);
        var afterMember = member.apply(zero);
        out("  %-24s %s   (%s)".formatted("→ 包一层 会员折扣", afterMember, member.describe()));

        var coupon = new CouponDiscount(new Coupon.FlatOff("SAVE30", Money.of("200", CNY), Money.of("30", CNY)), member);
        var afterCoupon = coupon.apply(zero);
        out("  %-24s %s   (%s)".formatted("→ 再包一层 优惠券", afterCoupon, coupon.describe()));

        var shipping = new ShippingFee(ShippingZone.DOMESTIC, 12, coupon);
        var afterShipping = shipping.apply(zero);
        out("  %-24s %s   (%s)".formatted("→ 再包一层 运费", afterShipping, shipping.describe()));

        PriceRule fullChain = new Tax(TaxRate.STANDARD, shipping);
        out("  %-24s %s   (%s)".formatted("→ 最外层 税", fullChain.apply(zero), "标准 13%"));

        heading("完整链组装");
        // 收尾 """ 的缩进决定了文本块的公共缩进量，这里比正文最深的一行还深，
        // 于是渲染出来是干净的阶梯，且不像 \s 那样留下尾随空格
        out("""
                new Tax(税率,
                  new ShippingFee(配送区域, 运费,
                    new CouponDiscount(优惠券,
                      new MemberDiscount(会员等级,
                        new ItemSubtotal(购物车))))))
                """);

        heading("顺序有影响 —— 装饰器不是无序的");
        var taxAfterShipping = new Tax(TaxRate.STANDARD,
                new ShippingFee(ShippingZone.OVERSEAS, 80, subtotal));
        var taxBeforeShipping = new ShippingFee(ShippingZone.OVERSEAS, 80,
                new Tax(TaxRate.STANDARD, subtotal));

        out("  零件完全相同：小计 254.30 + 海外运费 80 + 13% 税");
        out("  A  new Tax(new ShippingFee(小计))   税基含运费 : %s"
                .formatted(taxAfterShipping.apply(zero)));
        out("  B  new ShippingFee(new Tax(小计))   税基不含运费 : %s"
                .formatted(taxBeforeShipping.apply(zero)));
        out("  差 %s —— 恰好是运费被计的那部分税 80 × 13%%"
                .formatted(taxAfterShipping.apply(zero).minus(taxBeforeShipping.apply(zero))));

        heading("空购物车");
        var empty = new ItemSubtotal(List.of());
        out("  空车走完整条链: %s（装饰器不会因为下层空就炸）".formatted(
                new Tax(TaxRate.STANDARD,
                        new ShippingFee(ShippingZone.DOMESTIC, 12,
                                new MemberDiscount(MemberTier.GOLD, empty))).apply(zero)));

        out();
    }

    private static void heading(String title) {
        out();
        out("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}