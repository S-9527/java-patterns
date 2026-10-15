package demo.facade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 五个下游服务。
 *
 * <p><b>这些服务之间互相不认识。</b>它们只知道 {@link CheckoutContext} 这个共享载体，
 * 不知道自己被谁调用、也不知道别的服务做了什么。
 *
 * <p>这正是中介者改造后的形态 —— 对比 {@code Demo} 里画的"改造前"网状依赖图。
 *
 * <p>全部塞在一个文件里（都是包私有静态嵌套类）只是为了对照方便，
 * 真实项目里一个服务一个文件。
 */
final class Services {

    private Services() {
    }

    // ---------- 库存 ----------

    static final class InventoryService {
        private final Map<String, Integer> stock = new LinkedHashMap<>(CartDraft.DEFAULT_STOCK);

        /** 扣减库存。任何一行不足都返回失败，<b>不部分扣减</b>。 */
        String reserve(CheckoutContext ctx) {
            var shortages = new ArrayList<String>();
            for (var item : ctx.draft().items()) {
                var have = stock.getOrDefault(item.sku(), 0);
                if (have < item.quantity()) {
                    shortages.add("%s 库存 %d，需要 %d".formatted(item.sku(), have, item.quantity()));
                }
            }
            if (!shortages.isEmpty()) {
                ctx.record("库存", "扣减失败：" + String.join("；", shortages));
                return "库存不足：" + String.join("；", shortages);
            }
            for (var item : ctx.draft().items()) {
                stock.put(item.sku(), stock.get(item.sku()) - item.quantity());
            }
            ctx.stockReserved(true);
            ctx.record("库存", "扣减成功，剩余 " + snapshot());
            return null;
        }

        /** 回滚。门面在支付失败后调它。 */
        void release(CheckoutContext ctx) {
            for (var item : ctx.draft().items()) {
                stock.merge(item.sku(), item.quantity(), Integer::sum);
            }
            ctx.stockReserved(false);
            ctx.record("库存回滚", "已归还，当前 " + snapshot());
        }

        Map<String, Integer> snapshot() {
            return Map.copyOf(stock);
        }
    }

    // ---------- 定价 ----------

    static final class PricingService {
        /** 金额统一规整两位小数，否则 BigDecimal 会留下 "187.020" 这种尾巴。 */
        private static BigDecimal money(BigDecimal v) {
            return v.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        BigDecimal quote(CheckoutContext ctx) {
            var total = ctx.draft().items().stream()
                    .map(CartDraft.Item::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var quoted = money(total.multiply(new BigDecimal("0.9")));   // 会员 9 折
            ctx.quotedPrice(quoted);
            ctx.record("定价", "原价 %s → 折后 %s".formatted(money(total), quoted));
            return quoted;
        }
    }

    // ---------- 风控 ----------

    static final class RiskService {
        boolean evaluate(CheckoutContext ctx) {
            var amount = ctx.quotedPrice();
            if (amount.compareTo(new BigDecimal("5000")) > 0) {
                ctx.record("风控", "金额 %s 超限，拒绝".formatted(amount));
                return false;
            }
            ctx.riskPassed(true);
            ctx.record("风控", "通过");
            return true;
        }
    }

    // ---------- 支付 ----------

    static final class PaymentService {
        /** 返回 null 表示成功，否则是失败原因。 */
        String charge(CheckoutContext ctx, String channel) {
            if ("余额".equals(channel) && ctx.draft().customerId().startsWith("U-NOBAL")) {
                ctx.record("支付", "余额不足");
                return "余额不足";
            }
            ctx.paidAmount(ctx.quotedPrice());
            ctx.record("支付", "%s 扣款 %s 成功".formatted(channel, ctx.paidAmount()));
            return null;
        }
    }

    // ---------- 通知 ----------

    static final class NotificationService {
        void notify(CheckoutContext ctx, boolean success, String detail) {
            ctx.record("通知", (success ? "发送成功确认短信：" : "发送失败通知：") + detail);
        }
    }
}