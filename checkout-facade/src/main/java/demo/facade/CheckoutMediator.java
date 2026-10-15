package demo.facade;

/**
 * 结算中介者 —— <b>对内解耦</b>。
 *
 * <p>五个服务不再互相调用，全部只和中介者说话。
 *
 * <p><b>中介者的动机和门面完全不同</b>：
 * <ul>
 *   <li>门面：<b>客户端视角</b>的简化 —— "调用方不该知道要调五个服务"</li>
 *   <li>中介者：<b>模块间视角</b>的解耦 —— "这五个模块不该互相认识"</li>
 * </ul>
 *
 * <p>两者经常一起出现，但解决的是两个方向的问题。只做中介者不做门面，
 * 调用方还是要知道编排顺序；只做门面不做中介者，模块间的网状依赖依然存在。
 *
 * <h2>中介者不是银弹</h2>
 *
 * <p>GoF 原文明确说过中介者"不常被用到"，因为它的代价很大：
 * <ul>
 *   <li>编排逻辑集中到一个类，几十个服务时这个类会变成几千行</li>
 *   <li>加一种流程（比如"预售锁单"）可能要改中介者</li>
 * </ul>
 *
 * <p>真正让模块解耦的往往是<b>事件</b>而不是中介者 —— 见 {@code domain-events} 场景。
 * 什么时候中介者是合适的判据：<b>流程步骤多、顺序固定、且每一步都需要前一步的输出</b>。
 */
public final class CheckoutMediator {

    private final Services.InventoryService inventory;
    private final Services.PricingService pricing;
    private final Services.RiskService risk;
    private final Services.PaymentService payment;
    private final Services.NotificationService notification;

    public CheckoutMediator(Services.InventoryService inventory,
                            Services.PricingService pricing,
                            Services.RiskService risk,
                            Services.PaymentService payment,
                            Services.NotificationService notification) {
        this.inventory = inventory;
        this.pricing = pricing;
        this.risk = risk;
        this.payment = payment;
        this.notification = notification;
    }

    /**
     * 编排结算流程。
     *
     * <p>顺序不是随便定的，是<b>有依赖关系的</b>：
     * <ol>
     *   <li>定价 —— 风控要看金额，必须先算出来</li>
     *   <li>风控 —— 没过风控就不该占库存</li>
     *   <li>扣库存 —— 占了才能去收款</li>
     *   <li>收款 —— 收不回来要还库存</li>
     * </ol>
     *
     * <p>把这条依赖链显式写在这里，而不是散落在五个服务里互相调用 ——
     * 这就是中介者的价值。
     */
    public CheckoutContext.Result settle(CheckoutContext ctx, String channel) {
        ctx.orderId("SO-" + System.identityHashCode(ctx) % 100000);

        // 1. 定价
        pricing.quote(ctx);

        // 2. 风控（依赖定价结果）
        if (!risk.evaluate(ctx)) {
            notification.notify(ctx, false, "风控未通过");
            return ctx.fail("风控未通过");
        }

        // 3. 扣库存（依赖风控通过）
        var stockError = inventory.reserve(ctx);
        if (stockError != null) {
            notification.notify(ctx, false, stockError);
            return ctx.fail(stockError);
        }

        // 4. 收款（依赖库存已占用）
        var payError = payment.charge(ctx, channel);
        if (payError != null) {
            // 补偿：收不到钱就把库存还回去
            ctx.record("回滚", "支付失败，归还库存");
            inventory.release(ctx);
            notification.notify(ctx, false, payError);
            return ctx.fail(payError);
        }

        // 5. 通知
        notification.notify(ctx, true, "订单 %s 已支付 %s".formatted(ctx.orderId(), ctx.paidAmount()));
        return ctx.succeed();
    }
}