package demo.facade;

import java.util.List;

/**
 * 结算门面 —— <b>对外简化</b>。
 *
 * <p>调用方只需要知道一件事：{@code checkout(draft, channel)}。
 * 它不需要知道要调五个服务、不知道顺序、不知道失败要回滚。
 *
 * <p><b>门面的动机纯粹是"客户端视角的简化"</b>：把一串操作收成一个语义明确的方法。
 * 它和中介者是两回事 —— 见 {@link CheckoutMediator} 的 javadoc。
 */
public final class CheckoutFacade {

    private final Services.InventoryService inventory = new Services.InventoryService();
    private final Services.PricingService pricing = new Services.PricingService();
    private final Services.RiskService risk = new Services.RiskService();
    private final Services.PaymentService payment = new Services.PaymentService();
    private final Services.NotificationService notification = new Services.NotificationService();

    private final CheckoutMediator mediator;

    public CheckoutFacade() {
        // 门面依赖中介者，而不是直接依赖五个服务
        this.mediator = new CheckoutMediator(inventory, pricing, risk, payment, notification);
    }

    /**
     * 一句话完成结算。
     *
     * <p>这就是门面的全部价值：把"五个服务、六个步骤、失败要回滚"这段编排
     * 藏在一个方法后面。调用方看到的是一个语义完整的操作。
     */
    public CheckoutContext.Result checkout(CartDraft draft, String channel) {
        return mediator.settle(new CheckoutContext(draft), channel);
    }

    /** 门面暴露的中介者，让需要精细编排的调用方绕过门面。 */
    public CheckoutMediator mediator() {
        return mediator;
    }

    /** 库存快照，供演示用。 */
    public java.util.Map<String, Integer> stockSnapshot() {
        return inventory.snapshot();
    }
}