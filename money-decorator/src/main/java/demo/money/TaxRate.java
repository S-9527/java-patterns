package demo.money;

import java.math.BigDecimal;

/** 税率。 */
public enum TaxRate {

    ZERO("免税", BigDecimal.ZERO),
    STANDARD("标准 13%", new BigDecimal("0.13")),
    REDUCED("低税率 6%", new BigDecimal("0.06"));

    private final String label;
    private final BigDecimal rate;

    TaxRate(String label, BigDecimal rate) {
        this.label = label;
        this.rate = rate;
    }

    public String label() {
        return label;
    }

    public BigDecimal rate() {
        return rate;
    }
}