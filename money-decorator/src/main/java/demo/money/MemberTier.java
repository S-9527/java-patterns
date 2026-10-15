package demo.money;

import java.math.BigDecimal;

/** 会员等级。折扣率用 {@link BigDecimal} 而不是 double —— 原因见 {@link Money}。 */
public enum MemberTier {

    NONE(0),
    BRONZE(3),
    SILVER(5),
    GOLD(10);

    private final int discountPercent;

    MemberTier(int discountPercent) {
        this.discountPercent = discountPercent;
    }

    public int discountPercent() {
        return discountPercent;
    }

    public BigDecimal discountRate() {
        return BigDecimal.valueOf(discountPercent).movePointLeft(2);
    }
}