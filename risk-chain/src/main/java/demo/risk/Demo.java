package demo.risk;

import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * 责任链：风控校验。
 *
 * <p>核心是两种短路语义的区别，以及"顺序会改变结果"。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        heading("标准链");
        var chain = RiskChain.standard();
        for (int i = 0; i < chain.size(); i++) {
            out("  #%d %s".formatted(i, chain.rules().get(i).name()));
        }

        heading("正常订单");
        var good = Order.of("SO-1", "U-1001",
                new Line("SKU-1001", 2, Money.cny("39.90")),
                new Line("SKU-2044", 1, Money.cny("128.00")));
        out("  " + chain.run(good).describe());

        heading("一条订单踩多个坑");
        var bad = Order.of("SO-2", "U-BANNED-001",
                new Line("SKU-1001", 9, Money.cny("39.90")),     // 超限购(>5)
                new Line("SKU-TIMEOUT", 1, Money.cny("10.00")));  // 库存系统超时
        out("  订单: 用户在黑名单 + 买了 9 件 + 含一个会超时的 SKU");
        out();

        out("  模式 A  SHORT_CIRCUIT（教科书：命中即停）");
        var shortRun = chain.run(bad, RiskChain.Mode.SHORT_CIRCUIT);
        out("    → " + shortRun.describe());

        out("  模式 B  COLLECT_ALL（跑完全部，收集所有问题）");
        var allRun = chain.run(bad, RiskChain.Mode.COLLECT_ALL);
        out("    → " + allRun.describe());

        out();
        out("  差别就在这：SHORT_CIRCUIT 只报了黑名单，用户改完再提交才发现限购问题。");

        heading("Aborted 一定立即停");
        out("  库存系统挂了就别继续算金额了 —— 后面的结论不可信");
        var timeout = Order.of("SO-3", "U-1001", new Line("SKU-TIMEOUT", 1, Money.cny("99999.00")));
        var outcome = chain.run(timeout, RiskChain.Mode.COLLECT_ALL);
        out("  → " + outcome.describe());
        out("  只收集到 %d 条失败：库存抛异常后链就停了，后面的单笔限额没跑"
                .formatted(outcome.failures().size()));

        heading("顺序会改变结果");
        demoOrderSensitivity();

        heading("反面教材：教科书的 next() 链式组装");
        demoLegacyChaining();

        out();
    }

    private static void demoOrderSensitivity() {
        var expensive = new RiskRule.AmountCap(new BigDecimal("200.00"));
        var normal = RiskChain.standard();
        var order = Order.of("SO-4", "U-1001",
                new Line("SKU-1001", 2, Money.cny("150.00")));

        // 限额放在最前：立刻拒，用户不必等跑完
        var capFirst = normal.insertingAt(0, expensive);
        out("  限额插到链首:");
        capFirst.run(order).failures().forEach(f -> out("    " + f.describe()));

        // 限额放在最后：前面的规则先跑一遍才发现超限
        var capLast = normal.with(expensive);
        out("  限额接到链尾:");
        capLast.run(order).failures().forEach(f -> out("    " + f.describe()));

        out();
        out("  结果一样，但链首方案用户体验更好：早失败、少做无用功。");
        out("  代价是：顺序成了业务决策的一部分，不能随便调。");
    }

    private static void demoLegacyChaining() {
        var callLog = new ArrayList<String>();
        var rules = RiskChain.legacyChainedOrder(RiskRule.standard(), callLog);

        out("  教科书写法每条规则要这样：");
        out("""
                          @Override public RiskResult handle(Ctx ctx) {
                              if (rejected(ctx)) return reject(ctx);
                              return next.handle(ctx);   // ← 手工往下传，漏掉就静默断链
                          }""");
        out();
        out("  教科书链的执行顺序: %s".formatted(callLog));
        out("  本项目用的是「链持规则」而不是「规则持链」。");
        out("  规则只有一个 check() 方法，完全不知道自己在这条链上。");
        out("  顺序由 RiskChain.of(List) 决定，可以从配置或数据库读出来。");
        out();
        out("  代价：失去了「规则可自定义往下传」这种灵活性。多数场景不需要那种灵活性。");
        out("  规则数: %d".formatted(rules.size()));
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