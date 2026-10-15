package demo.facade;

import java.math.BigDecimal;
import java.util.List;

/**
 * 门面 + 中介者。
 *
 * <p>重点是两者的<b>动机不同</b>：门面为客户端简化，中介者为模块解耦。
 * Demo 里把改造前后的依赖图画出来对照。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        heading("改造前：五个模块互相调用（网状依赖）");
        out(meshDiagram());
        out();
        out("  每个服务要知道另外四个的存在。改任何一个，可能要改另外四个。");
        out("  而且这五个模块在别的流程（预售、秒杀、换货）里还得重新连一遍。");

        heading("改造后：全部只和中介者说话");
        out(starDiagram());
        out();
        out("  模块之间 0 条直接依赖。连线数量可能差不多，甚至更多，");
        out("  但关键是「谁依赖谁」变了：现在只有中介者知道全部流程。");
        out("  加一种流程只改中介者，五个模块一行都不用动。");

        heading("正常结算");
        var facade = new CheckoutFacade();
        var draft = new CartDraft("U-1001", List.of(
                new CartDraft.Item("SKU-1001", 2, new BigDecimal("39.90")),
                new CartDraft.Item("SKU-2044", 1, new BigDecimal("128.00"))));

        var result = facade.checkout(draft, "支付宝");
        print(result);
        out();
        out("  调用方只知道 checkout(draft, channel) 这一个方法。");
        out("  它不知道要调五个服务，也不知道失败要还库存。");

        heading("⚠ 支付失败：中介者负责回滚");
        var noBalance = new CartDraft("U-NOBALANCE", List.of(
                new CartDraft.Item("SKU-1001", 1, new BigDecimal("39.90"))));
        var stockBefore = facade.stockSnapshot();

        var failed = facade.checkout(noBalance, "余额");
        print(failed);
        out();
        out("  库存扣减后又归还了 —— 中介者的补偿逻辑");
        out("  %s".formatted(stockBefore.equals(facade.stockSnapshot())
                ? "库存前后一致：SKU-1001 仍是 " + facade.stockSnapshot().get("SKU-1001")
                : "⚠ 库存不一致，回滚有 bug"));

        heading("风控拦截：不该占库存");
        var huge = new CartDraft("U-1001", List.of(
                new CartDraft.Item("SKU-1001", 1, new BigDecimal("9999.00"))));
        var blocked = facade.checkout(huge, "支付宝");
        print(blocked);
        out();
        out("  风控没过 → 流程根本没走到扣库存那一步。");
        out("  编排顺序在中介者里是显式的，这个依赖关系一目了然。");

        heading("门面 vs 中介者");
        out("""
                          门面                中介者
                站在谁    客户端视角            模块间视角
                解决什么  调用方要知道调哪些    模块之间不该互相认识
                典型实现  一个门面方法包住一串   所有模块只和一个协调者说话
                不做会怎样 调用方到处写编排      网状依赖，加流程要改N个模块
                能单独要吗 能（简化入口）        能（但通常配合门面一起上）""");
        out();
        out("  只做门面不做中介者 → 模块间网状依赖依然存在");
        out("  只做中介者不做门面 → 调用方仍要知道编排顺序");

        heading("⚠ 中介者不是银弹");
        out("  GoF 原文自己就说过中介者「不常被用到」，代价是：");
        out("    - 编排集中到一个类，服务多时它会变成几千行");
        out("    - 加一种新流程（预售锁单）可能又得改它");
        out();
        out("  真正让模块松耦合的往往是事件，不是中介者 —— 见 domain-events 场景。");
        out("  中介者合适的前提：步骤多、顺序固定、每步都要上一步的输出。");
        out("  本项目只有 5 个服务，真项目到 50 个就该考虑换成事件驱动了。");

        System.out.println();
    }

    private static void print(CheckoutContext.Result result) {
        var trace = result instanceof CheckoutContext.Result.Accepted a
                ? a.trace()
                : ((CheckoutContext.Result.Rejected) result).trace();

        out("  执行轨迹：");
        trace.forEach(s -> out("    %-10s %s".formatted(s.name(), s.detail())));
        out();
        if (result instanceof CheckoutContext.Result.Accepted a) {
            out("  ✓ 结算成功  订单 %s  应付 %s  实付 %s".formatted(a.orderId(), a.quoted(), a.paid()));
        } else {
            out("  ✗ 结算失败：%s".formatted(((CheckoutContext.Result.Rejected) result).reason()));
        }
    }

    private static String meshDiagram() {
        return """
                        ┌─────────┐
                        │  库存   │
                        └────┬────┘
              ┌──────────────┼──────────────┐
              │              │              │
          ┌───┴───┐      ┌───┴───┐      ┌───┴───┐
          │ 定价  │      │ 结算  │      │ 通知  │
          └───┬───┘      └───┬───┘      └───┬───┘
              └──────────────┼──────────────┘
                             │
                五个模块两两之间都在互相调用""";
    }

    private static String starDiagram() {
        return """
                              ┌────────┐
                              │  库存  │
                              └───┬────┘
                  ┌───────────────┼───────────────┐
                  │               │               │
              ┌───┴───┐       ┌───┴───┐       ┌───┴───┐
              │ 定价  │       │ 中介者 │       │ 通知  │
              └───┬───┘       └───┬───┘       └───┬───┘
                  └───────────────┼───────────────┘
                                  │
                模块之间 0 条直接依赖，只有中介者知道全部""";
    }

    private static void heading(String title) {
        out();
        out("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}