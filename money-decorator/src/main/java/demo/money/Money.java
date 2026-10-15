package demo.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * 金额。不可变值对象。
 *
 * <p>这个类存在的意义是<b>把"金额"和 {@code double} 隔开</b>。{@code double} 是二进制浮点，
 * 连 {@code 0.1} 都存不精确，累加后必然出现 {@code 0.30000000000000004} 这种值。
 * 金额必须用 {@link BigDecimal}。
 *
 * <p>另一个坑是 {@code BigDecimal} 的 {@code equals} 对 scale 敏感：
 * {@code new BigDecimal("1.0").equals(new BigDecimal("1.00"))} 是 {@code false}，
 * 而 {@code compareTo} 返回 0。如果直接把 {@code BigDecimal} 塞进 record 的组件里，
 * 断言会莫名其妙地挂掉。所以这里的规范构造器统一把 scale 规整到 {@value #SCALE} 位。
 */
public record Money(BigDecimal amount, Currency currency) {

    /** 所有金额统一保留两位小数。 */
    public static final int SCALE = 2;

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("金额不能为 null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("币种不能为 null");
        }
        // 规整 scale，让 equals 和 compareTo 对同一笔金额给出相同答案
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * 按系数缩放，例如打折 {@code times(new BigDecimal("0.9"))}。
     * 注意这里用的是 {@link BigDecimal#multiply}，不是 double 乘法。
     */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    /** 按百分比减免，{@code percentageOff(10)} 表示打九折。 */
    public Money percentageOff(int percent) {
        return times(BigDecimal.ONE.subtract(
                BigDecimal.valueOf(percent).movePointLeft(2)));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "币种不一致，无法运算：%s vs %s".formatted(currency, other.currency));
        }
    }

    @Override
    public String toString() {
        return "%s %s".formatted(currency.getCurrencyCode(), amount);
    }
}