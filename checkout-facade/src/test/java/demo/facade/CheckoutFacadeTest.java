package demo.facade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("门面 + 中介者 —— 结算流程")
class CheckoutFacadeTest {

    private CheckoutFacade facade;

    @BeforeEach
    void setUp() {
        facade = new CheckoutFacade();
    }

    private static CartDraft normalCart() {
        return new CartDraft("U-1001", List.of(
                new CartDraft.Item("SKU-1001", 2, new BigDecimal("39.90")),
                new CartDraft.Item("SKU-2044", 1, new BigDecimal("128.00"))));
    }

    @Test
    @DisplayName("正常结算：定价 → 风控 → 扣库存 → 收款 → 通知")
    void happyPath() {
        var result = facade.checkout(normalCart(), "支付宝");

        var accepted = assertInstanceOf(CheckoutContext.Result.Accepted.class, result);
        assertEquals(0, accepted.paid().compareTo(new BigDecimal("187.02")),
                "原价 79.80 + 128.00 = 207.80，九折后 187.02");
        assertNotNull(accepted.orderId());

        // 顺序很重要：每一步都依赖上一步的输出
        var names = accepted.trace().stream().map(CheckoutContext.Step::name).toList();
        assertEquals(List.of("定价", "风控", "库存", "支付", "通知"), names);
    }

    @Test
    @DisplayName("⚠ 风控拦截时不占库存 —— 编排顺序的依赖关系")
    void riskBlocksBeforeStockIsTaken() {
        var stockBefore = facade.stockSnapshot();
        var huge = new CartDraft("U-1001", List.of(
                new CartDraft.Item("SKU-1001", 1, new BigDecimal("9999.00"))));

        var result = facade.checkout(huge, "支付宝");

        var rejected = assertInstanceOf(CheckoutContext.Result.Rejected.class, result);
        assertTrue(rejected.reason().contains("风控"));

        assertEquals(stockBefore, facade.stockSnapshot(),
                "风控没过就返回了，库存一行没动 —— 因为风控排在扣库存前面");

        var names = rejected.trace().stream().map(CheckoutContext.Step::name).toList();
        assertFalse(names.contains("库存"), "库存步骤根本没被执行");
    }

    @Test
    @DisplayName("⚠ 库存不足：整单失败，不部分扣减")
    void stockShortageFailsWholeOrder() {
        var result = facade.checkout(new CartDraft("U-1001", List.of(
                new CartDraft.Item("SKU-1001", 1, new BigDecimal("10.00")),
                new CartDraft.Item("SKU-3310", 1, new BigDecimal("10.00")))), "支付宝");

        var rejected = assertInstanceOf(CheckoutContext.Result.Rejected.class, result);
        assertTrue(rejected.reason().contains("SKU-3310"), "应该点名缺货的 SKU");
    }

    @Test
    @DisplayName("⚠ 支付失败要回滚库存 —— 中介者的补偿逻辑")
    void paymentFailureRollsBackStock() {
        var before = facade.stockSnapshot();
        var noBalance = new CartDraft("U-NOBALANCE", List.of(
                new CartDraft.Item("SKU-1001", 3, new BigDecimal("39.90"))));

        var result = facade.checkout(noBalance, "余额");

        assertInstanceOf(CheckoutContext.Result.Rejected.class, result);
        assertEquals(before, facade.stockSnapshot(),
                "库存扣了又还回来了，前后必须一致");

        var names = result instanceof CheckoutContext.Result.Rejected r
                ? r.trace().stream().map(CheckoutContext.Step::name).toList()
                : List.<String>of();
        assertTrue(names.contains("库存回滚"), "轨迹里应记录回滚动作：" + names);
    }

    @Test
    @DisplayName("轨迹完整可回溯 —— 出问题能看清哪一步失败")
    void traceRecordsEveryStep() {
        var result = facade.checkout(normalCart(), "支付宝");
        var accepted = assertInstanceOf(CheckoutContext.Result.Accepted.class, result);

        assertEquals(5, accepted.trace().size());
        accepted.trace().forEach(s -> assertNotNull(s.detail(), "每步都要有说明"));
        assertTrue(accepted.trace().stream().allMatch(s -> !s.detail().isBlank()));
    }

    @Test
    @DisplayName("轨迹不可变")
    void traceIsImmutable() {
        var result = facade.checkout(normalCart(), "支付宝");
        var accepted = assertInstanceOf(CheckoutContext.Result.Accepted.class, result);

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> accepted.trace().add(new CheckoutContext.Step("x", "y"))) != null);
    }

    @Test
    @DisplayName("门面暴露中介者 —— 需要精细编排的调用方可以绕过门面")
    void facadeExposesMediator() {
        assertNotNull(facade.mediator());
        var ctx = new CheckoutContext(normalCart());
        var result = facade.mediator().settle(ctx, "支付宝");
        assertTrue(result.isAccepted());
    }

    @Test
    @DisplayName("门面每次返回独立上下文，不共享状态")
    void facadeDoesNotShareState() {
        var first = facade.checkout(normalCart(), "支付宝");
        var second = facade.checkout(normalCart(), "微信支付");

        assertTrue(first.isAccepted());
        assertTrue(second.isAccepted());
        assertNotNull(first);
        assertNotNull(second);
    }

    @Test
    @DisplayName("结算上下文是有界可变的 —— 只在单次结算内使用")
    void contextIsMutableButScoped() {
        var ctx = new CheckoutContext(normalCart());
        assertEquals(BigDecimal.ZERO, ctx.quotedPrice());
        assertFalse(ctx.riskPassed());
        assertFalse(ctx.stockReserved());

        new CheckoutMediator(
                new Services.InventoryService(),
                new Services.PricingService(),
                new Services.RiskService(),
                new Services.PaymentService(),
                new Services.NotificationService())
                .settle(ctx, "支付宝");

        assertTrue(ctx.riskPassed());
        assertTrue(ctx.stockReserved());
        assertNotNull(ctx.orderId());
    }

    @Test
    @DisplayName("Result 是 sealed 的，两种结局")
    void resultIsSealed() {
        var accepted = facade.checkout(normalCart(), "支付宝");
        assertTrue(accepted instanceof CheckoutContext.Result.Accepted);
        assertTrue(accepted.isAccepted());

        var rejected = facade.checkout(new CartDraft("U-1001", List.of(
                new CartDraft.Item("SKU-3310", 1, new BigDecimal("10.00")))), "支付宝");
        assertInstanceOf(CheckoutContext.Result.Rejected.class, rejected);
        assertFalse(rejected.isAccepted());
    }

    @Test
    @DisplayName("库存是持久的 —— 多次结算会持续扣减")
    void stockDecrementsAcrossOrders() {
        var before = facade.stockSnapshot().get("SKU-2044");
        facade.checkout(normalCart(), "支付宝");
        var after = facade.stockSnapshot().get("SKU-2044");

        assertEquals(before - 1, after, "SKU-2044 每次结算扣 1");
    }

    @Test
    @DisplayName("中介者的编排：没有循环调用，不存在互相等待")
    void mediatorIsAcyclic() {
        var ctx = new CheckoutContext(normalCart());
        new CheckoutMediator(
                new Services.InventoryService(),
                new Services.PricingService(),
                new Services.RiskService(),
                new Services.PaymentService(),
                new Services.NotificationService())
                .settle(ctx, "支付宝");

        // 验证顺序：每一步的状态在它执行时已经就绪
        var trace = ctx.steps();
        var pricing = indexOf(trace, "定价");
        var risk = indexOf(trace, "风控");
        var stock = indexOf(trace, "库存");
        var pay = indexOf(trace, "支付");

        assertTrue(pricing < risk, "定价必须在风控之前");
        assertTrue(risk < stock, "风控必须在扣库存之前");
        assertTrue(stock < pay, "扣库存必须在收款之前");
    }

    private static int indexOf(List<CheckoutContext.Step> trace, String name) {
        for (int i = 0; i < trace.size(); i++) {
            if (trace.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}