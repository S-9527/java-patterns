package demo.money;

/** 税。链上最外层通常就是它。 */
public record Tax(TaxRate rate, PriceRule next) implements PriceRule {

    @Override
    public Money apply(Money amount) {
        var subtotal = next.apply(amount);
        return subtotal.plus(subtotal.times(rate.rate()));
    }

    public String describe() {
        return rate.label();
    }
}