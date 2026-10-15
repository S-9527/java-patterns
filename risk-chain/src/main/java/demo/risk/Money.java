package demo.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** 金额。各模块自带一份 —— 模块之间零依赖。 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money cny(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CNY"));
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount;
    }
}