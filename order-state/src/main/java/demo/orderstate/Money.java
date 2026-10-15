package demo.orderstate;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * 金额。这里刻意做成极简版 —— 每个模块自带一份，因为本项目的硬约束是<b>模块之间零依赖</b>，
 * 拷走一个模块要依然能编译。完整版（两位小数规整、币种校验）在 money-decorator 模块里。
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static Money cny(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CNY"));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount), currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount;
    }
}