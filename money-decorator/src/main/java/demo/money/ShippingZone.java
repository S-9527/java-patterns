package demo.money;

/** 配送区域。满额包邮门槛各不同。 */
public enum ShippingZone {

    DOMESTIC("中国大陆", 99),
    HK_MACAU("港澳台", 150),
    OVERSEAS("海外", 0);

    private final String label;
    private final int freeThreshold;

    ShippingZone(String label, int freeThreshold) {
        this.label = label;
        this.freeThreshold = freeThreshold;
    }

    public String label() {
        return label;
    }

    /** 0 表示不包邮。 */
    public int freeThreshold() {
        return freeThreshold;
    }

    public boolean hasFreeShipping() {
        return freeThreshold > 0;
    }
}