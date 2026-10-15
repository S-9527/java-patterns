package demo.risk;

import java.util.List;

/** 待检查的订单。 */
record Order(String id, String customerId, List<Line> lines) {

    Order {
        lines = List.copyOf(lines);
    }

    static Order of(String id, String customerId, Line... lines) {
        return new Order(id, customerId, List.of(lines));
    }
}

/** 购物车一行。 */
record Line(String sku, int quantity, Money unitPrice) {
}