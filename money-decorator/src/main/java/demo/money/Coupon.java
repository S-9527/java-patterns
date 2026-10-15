package demo.money;

import java.math.BigDecimal;

/**
 * 优惠券：满 {@code minSpend} 可用，满减 {@code amount} 或打 {@code percentOff} 折，二选一。
 *
 * <p>用 sealed 表达"减法券 or 乘法券"，正好是模式匹配 switch 的用武之地：
 * {@link CouponDiscount} 里不需要一串 {@code instanceof}。
 */
public sealed interface Coupon {

    String code();

    Money minSpend();

    /** 当前金额是否满足使用门槛。 */
    default boolean usableFor(Money subtotal) {
        return subtotal.amount().compareTo(minSpend().amount()) >= 0;
    }

    record FlatOff(String code, Money minSpend, Money amount) implements Coupon {
        public FlatOff {
            if (amount.isNegative()) {
                throw new IllegalArgumentException("减免额不能为负");
            }
        }
    }

    record PercentOff(String code, Money minSpend, int percentOff) implements Coupon {
        public PercentOff {
            if (percentOff <= 0 || percentOff >= 100) {
                throw new IllegalArgumentException("折扣必须在 1~99 之间：%d".formatted(percentOff));
            }
        }

        public BigDecimal rate() {
            return BigDecimal.valueOf(percentOff).movePointLeft(2);
        }
    }
}