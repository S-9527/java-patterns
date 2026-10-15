package demo.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 一条风控规则。
 *
 * <p>注意这个接口<b>只有一个方法</b>。责任链模式下，规则彼此不认识，
 * 链的编排完全交给 {@link RiskChain}。如果规则互相调用，就不是责任链了。
 *
 * <p>{@link Order} 和 {@link RiskContext} 是两个入参而不是一个 DTO ——
 * 因为一条规则通常只关心其中一部分，给它整个聚合等于把不该看的也给它看。
 */
public interface RiskRule {

    String name();

    /**
     * @param order 待检查的订单
     * @param ctx   环境：已购数量、历史订单数、当前时间等
     */
    RiskResult check(Order order, RiskContext ctx);

    // ============ 四条具体规则 ============

    /** 黑名单：命中直接拒。 */
    record Blacklist(Set<String> blockedUsers) implements RiskRule {

        public Blacklist {
            blockedUsers = Set.copyOf(blockedUsers);
        }

        @Override
        public String name() {
            return "黑名单";
        }

        @Override
        public RiskResult check(Order order, RiskContext ctx) {
            return blockedUsers.contains(order.customerId())
                    ? new RiskResult.Rejected(name(), "用户在黑名单中", ctx.ruleIndex(this))
                    : new RiskResult.Passed();
        }
    }

    /** 限购：单笔数量超过上限就拒。 */
    record PurchaseLimit(int maxQuantityPerOrder) implements RiskRule {

        @Override
        public String name() {
            return "限购";
        }

        @Override
        public RiskResult check(Order order, RiskContext ctx) {
            int quantity = order.lines().stream().mapToInt(Line::quantity).sum();
            return quantity > maxQuantityPerOrder
                    ? new RiskResult.Rejected(name(),
                            "单笔最多 %d 件，本次 %d 件".formatted(maxQuantityPerOrder, quantity),
                            ctx.ruleIndex(this))
                    : new RiskResult.Passed();
        }
    }

    /**
     * 库存检查。
     *
     * <p>刻意做成 <b>可能中断</b>（抛异常）：模拟库存系统超时。
     * 这就是 {@link RiskResult.Aborted} 存在的理由 —— 和"库存不足"是两回事。
     */
    record StockCheck(java.util.Map<String, Integer> stockBySku,
                      Set<String> alwaysTimeoutSkus) implements RiskRule {

        public StockCheck {
            stockBySku = java.util.Map.copyOf(stockBySku);
            alwaysTimeoutSkus = Set.copyOf(alwaysTimeoutSkus);
        }

        @Override
        public String name() {
            return "库存";
        }

        @Override
        public RiskResult check(Order order, RiskContext ctx) {
            if (order.lines().stream().anyMatch(l -> alwaysTimeoutSkus.contains(l.sku()))) {
                throw new IllegalStateException("库存服务超时");
            }
            var shortages = order.lines().stream()
                    .filter(l -> stockBySku.getOrDefault(l.sku(), 0) < l.quantity())
                    .map(l -> "%s 缺货（要 %d，有 %d）"
                            .formatted(l.sku(), l.quantity(), stockBySku.getOrDefault(l.sku(), 0)))
                    .toList();

            return shortages.isEmpty()
                    ? new RiskResult.Passed()
                    : new RiskResult.Rejected(name(), String.join("；", shortages),
                            ctx.ruleIndex(this));
        }
    }

    /** 单笔金额上限。 */
    record AmountCap(BigDecimal maxAmount) implements RiskRule {

        @Override
        public String name() {
            return "单笔限额";
        }

        @Override
        public RiskResult check(Order order, RiskContext ctx) {
            var total = order.lines().stream()
                    .map(l -> l.unitPrice().amount().multiply(BigDecimal.valueOf(l.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return total.compareTo(maxAmount) > 0
                    ? new RiskResult.Rejected(name(),
                            "单笔最多 %s，本次 %s".formatted(maxAmount, total), ctx.ruleIndex(this))
                    : new RiskResult.Passed();
        }
    }

    /** 规则清单，供 Demo 演示"顺序可插拔"。 */
    static List<RiskRule> standard() {
        return List.of(
                new Blacklist(Set.of("U-BANNED-001")),
                new PurchaseLimit(5),
                new StockCheck(java.util.Map.of("SKU-1001", 100, "SKU-2044", 50), Set.of("SKU-TIMEOUT")),
                new AmountCap(new BigDecimal("1000.00")));
    }
}