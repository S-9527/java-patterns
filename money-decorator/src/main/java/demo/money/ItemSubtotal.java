package demo.money;

import java.util.List;

/**
 * 链的起点：商品小计。
 *
 * <p>它是唯一一个<b>不使用</b>传入金额内容的规则 —— 结果只由购物车决定。
 * 所以调用整条链时，起点应该收到 {@link Money#zero}，币种由它提供。
 *
 * <p>这里能看出装饰器的一个别扭之处：接口签名被"和金额做算术"绑架了，
 * 导致起点也得接受一个它并不需要的参数。
 * 另一种设计是把签名改成 {@code Money apply(PricingContext)}，让每条规则各取所需 ——
 * 但那样 {@link MemberDiscount} 就得自己从 context 里掏 {@code next}，耦合反而更重。
 * 两种都不是完美方案，docs 里会展开讲。
 */
public record ItemSubtotal(List<LineItem> items) implements PriceRule {

    public ItemSubtotal {
        items = List.copyOf(items);
    }

    @Override
    public Money apply(Money base) {
        // base 是同币种的零值，reduce 的 identity 就是它本身
        return items.stream().map(LineItem::lineTotal).reduce(base, Money::plus);
    }

    public int lineCount() {
        return items.size();
    }
}