package demo.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Money —— 为什么金额不能用 double，以及 BigDecimal 的 scale 陷阱")
class MoneyTest {

    private static final Currency CNY = Currency.getInstance("CNY");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    @DisplayName("double 连 0.1 都存不精确，这就是金额必须用 BigDecimal 的原因")
    void doubleIsNotUsableForMoney() {
        double naive = 0.1 + 0.2;
        assertTrue(naive != 0.3, "double 运算结果不等于字面量，这是它不能用来算钱的原因");

        BigDecimal exact = new BigDecimal("0.1").add(new BigDecimal("0.2"));
        assertEquals(new BigDecimal("0.3"), exact, "BigDecimal 十进制字符串构造则精确");
    }

    @Test
    @DisplayName("BigDecimal.equals 对 scale 敏感，compareTo 不敏感 —— 所以构造器要规整 scale")
    void bigDecimalEqualsIsScaleSensitive() {
        // BigDecimal 自己的坑：值相等但 equals 为 false
        assertEquals(0, new BigDecimal("1.0").compareTo(new BigDecimal("1.00")));
        assertEquals(false, new BigDecimal("1.0").equals(new BigDecimal("1.00")),
                "compareTo 返回 0 但 equals 是 false —— 放进 record 组件里断言会莫名其妙挂掉");

        // Money 的规范构造器把 scale 统一到两位，于是 equals 恢复正常
        assertEquals(Money.of("1.0", CNY), Money.of("1.00", CNY));
    }

    @Test
    @DisplayName("两位小数、四舍五入")
    void roundsHalfUpToTwoDecimals() {
        assertEquals(Money.of("1.00", CNY), Money.of("0.999", CNY));
        assertEquals(Money.of("1.01", CNY), Money.of("1.005", CNY), "HALF_UP 而非 HALF_EVEN");
    }

    @Test
    @DisplayName("币种不同直接拒绝运算，不做隐式换汇")
    void rejectsCrossCurrencyArithmetic() {
        var cny = Money.of("100", CNY);
        var usd = Money.of("100", USD);
        var e = assertThrows(IllegalArgumentException.class, () -> cny.plus(usd));
        assertTrue(e.getMessage().contains("币种不一致"));
    }

    @Test
    @DisplayName("百分比减免：10% off 是乘 0.9，不是减 0.1")
    void percentageOff() {
        assertEquals(Money.of("90.00", CNY), Money.of("100.00", CNY).percentageOff(10));
        assertEquals(Money.of("0.00", CNY), Money.of("100.00", CNY).percentageOff(100));
    }

    @Test
    @DisplayName("零和负的判定")
    void signChecks() {
        assertTrue(Money.zero(CNY).isZero());
        assertTrue(Money.of("-1", CNY).isNegative());
        assertTrue(!Money.zero(CNY).isNegative());
    }

    @Test
    @DisplayName("toString 带币种代码，便于排查")
    void toStringIncludesCurrency() {
        assertEquals("CNY 12.34", Money.of("12.34", CNY).toString());
    }
}