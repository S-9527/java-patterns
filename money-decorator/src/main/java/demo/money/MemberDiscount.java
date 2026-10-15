package demo.money;

/**
 * 会员折扣 —— 装饰器的标准长相。
 *
 * <p>它<b>不实现任何算术细节以外的东西</b>：持有 {@code next}，先问下游要结果，再打折。
 * 想把会员折扣去掉，就是不包这一层，其余规则一行都不用改 —— 这正是装饰器的价值。
 *
 * @param tier 会员等级
 * @param next 链上的下一环
 */
public record MemberDiscount(MemberTier tier, PriceRule next) implements PriceRule {

    @Override
    public Money apply(Money amount) {
        var subtotal = next.apply(amount);
        return subtotal.percentageOff(tier.discountPercent());
    }

    public String describe() {
        return tier == MemberTier.NONE ? "无折扣" : tier + " %d%% off".formatted(tier.discountPercent());
    }
}