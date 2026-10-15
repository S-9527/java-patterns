package demo.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("风控链 —— 责任链")
class RiskChainTest {

    private static Order order(String customerId, Line... lines) {
        return Order.of("SO-1", customerId, lines);
    }

    private static Line line(String sku, int qty, String price) {
        return new Line(sku, qty, Money.cny(price));
    }

    @Test
    @DisplayName("正常订单全过")
    void cleanOrderPasses() {
        var outcome = RiskChain.standard().run(order("U-1001",
                line("SKU-1001", 2, "39.90"), line("SKU-2044", 1, "128.00")));

        assertTrue(outcome.failures().isEmpty());
        assertEquals("全部 4 条规则通过", outcome.describe());
        assertEquals(4, outcome.ruleCount(), "标准链有 4 条规则");
    }

    @Test
    @DisplayName("黑名单命中")
    void blacklistRejects() {
        var outcome = RiskChain.standard().run(order("U-BANNED-001", line("SKU-1001", 1, "10.00")));

        assertEquals(1, outcome.failures().size());
        assertInstanceOf(RiskResult.Rejected.class, outcome.failures().getFirst());
        assertEquals("黑名单", ((RiskResult.Rejected) outcome.failures().getFirst()).rule());
    }

    @Test
    @DisplayName("限购：数量是所有行相加，不是单行数量")
    void purchaseLimitSumsAllLines() {
        // 每行 3 件都没超 5，但总共 6 件超了
        var outcome = RiskChain.standard().run(order("U-1001",
                line("SKU-1001", 3, "10.00"), line("SKU-2044", 3, "10.00")));

        assertEquals("限购", ((RiskResult.Rejected) outcome.failures().getFirst()).rule());
    }

    @Test
    @DisplayName("库存不足：列出所有缺货行")
    void stockShortage() {
        var chain = RiskChain.of(List.of(
                new RiskRule.StockCheck(java.util.Map.of("SKU-A", 1, "SKU-B", 0), Set.of())));
        var outcome = chain.run(order("U-1001", line("SKU-A", 2, "10.00"), line("SKU-B", 1, "10.00")));

        var rejected = (RiskResult.Rejected) outcome.failures().getFirst();
        assertEquals("库存", rejected.rule());
        assertTrue(rejected.reason().contains("SKU-A"), "缺货原因里应列出缺货的 SKU");
        assertTrue(rejected.reason().contains("SKU-B"));
    }

    @Test
    @DisplayName("⚠ SHORT_CIRCUIT 只报第一个问题，COLLECT_ALL 报全部")
    void shortCircuitVsCollectAll() {
        // 黑名单 + 超限购 + 超限额，三个问题同时存在
        var bad = order("U-BANNED-001", line("SKU-1001", 9, "500.00"));

        var shortRun = RiskChain.standard().run(bad, RiskChain.Mode.SHORT_CIRCUIT);
        var allRun = RiskChain.standard().run(bad, RiskChain.Mode.COLLECT_ALL);

        assertEquals(1, shortRun.failures().size(), "命中即停只报第一个");
        assertEquals(3, allRun.failures().size(), "跑完能同时报出黑名单、限购和限额");
        assertEquals("黑名单", ((RiskResult.Rejected) shortRun.failures().getFirst()).rule());

        // COLLECT_ALL 的价值：一次交互把三个问题全告诉用户，
        // 而不是让用户改完黑名单再提交一次才发现超限购
        assertEquals(
                List.of("黑名单", "限购", "单笔限额"),
                allRun.failures().stream()
                        .map(f -> ((RiskResult.Rejected) f).rule())
                        .toList(),
                "收集到的三个问题，按链上顺序");
    }

    @Test
    @DisplayName("⚠ Aborted 无条件终止 —— 即使是 COLLECT_ALL")
    void abortedAlwaysStops() {
        var chain = RiskChain.of(List.of(
                new RiskRule.PurchaseLimit(1),
                new RiskRule.StockCheck(java.util.Map.of(), Set.of("SKU-TIMEOUT")),
                new RiskRule.AmountCap(new BigDecimal("1.00"))));

        // 第 1 条超限 → 收集；第 2 条超时 → 终止；第 3 条根本不会跑
        var outcome = chain.run(order("U-1001", line("SKU-TIMEOUT", 5, "100.00")),
                RiskChain.Mode.COLLECT_ALL);

        assertEquals(2, outcome.failures().size());
        assertInstanceOf(RiskResult.Rejected.class, outcome.failures().getFirst());
        assertInstanceOf(RiskResult.Aborted.class, outcome.failures().get(1),
                "超时的规则应被翻译成 Aborted");
    }

    @Test
    @DisplayName("⚠ 规则抛异常不会炸掉整条链 —— 异常被降级成 Aborted")
    void ruleExceptionDegradesToAborted() {
        var chain = RiskChain.of(List.of(
                new RiskRule.StockCheck(java.util.Map.of(), Set.of("SKU-BOOM"))));
        var outcome = chain.run(order("U-1001", line("SKU-BOOM", 1, "10.00")));

        assertEquals(1, outcome.failures().size());
        var aborted = assertInstanceOf(RiskResult.Aborted.class, outcome.failures().getFirst());
        assertEquals("库存", aborted.rule());
        assertEquals("库存服务超时", aborted.cause());
    }

    @Test
    @DisplayName("Aborted 和 Rejected 语义不同：前者该重试，后者重试没用")
    void abortedIsNotRejected() {
        // 业务拒绝：重试一万次还是被拒
        RiskResult rejected = new RiskResult.Rejected("黑名单", "用户在黑名单中", 0);
        // 系统中断：应该重试或降级
        RiskResult aborted = new RiskResult.Aborted("库存", "连接超时");

        // 声明成 RiskResult，switch 靠 sealed 穷尽 —— 编译器保证不会漏
        var advice = switch (aborted) {
            case RiskResult.Rejected r -> "拒绝：提示用户，重试无用";
            case RiskResult.Aborted a -> "中断：应重试或降级";
            case RiskResult.Passed p -> "通过";
            case RiskResult.ChainOutcome co -> co.describe();
        };
        assertEquals("中断：应重试或降级", advice);

        assertFalse(rejected instanceof RiskResult.Aborted);
        assertFalse(aborted instanceof RiskResult.Rejected);
        assertTrue(rejected.describe().startsWith("拒绝"));
        assertTrue(aborted.describe().startsWith("中断"));
    }

    @Test
    @DisplayName("⚠ 顺序改变结果 —— 链首限额 vs 链尾限额")
    void orderMatters() {
        // 同一张订单，同样两条规则，只改先后：限购 5 件 vs 限额 200 元
        var purchaseLimit = new RiskRule.PurchaseLimit(5);
        var amountCap = new RiskRule.AmountCap(new BigDecimal("200.00"));

        // 方案一：限额排链首
        var capFirst = RiskChain.of(List.of(amountCap, purchaseLimit));
        // 方案二：限购排链首
        var limitFirst = RiskChain.of(List.of(purchaseLimit, amountCap));

        // 订单 9 件、单价 30 → 数量 9 > 5，金额 270 > 200，两个问题同时成立
        var order = order("U-1001", line("SKU-1001", 9, "30.00"));

        // COLLECT_ALL：两个问题都报出来，区别只在顺序
        assertEquals(List.of("单笔限额", "限购"), capFirst.run(order).failures().stream()
                        .map(f -> ((RiskResult.Rejected) f).rule()).toList(),
                "限额排链首：限额先出现在列表里");

        assertEquals(List.of("限购", "单笔限额"), limitFirst.run(order).failures().stream()
                        .map(f -> ((RiskResult.Rejected) f).rule()).toList(),
                "限购排链首：限购先出现在列表里");

        // SHORT_CIRCUIT：只有第一个报出来，顺序差异才真正影响用户看到什么
        assertEquals(List.of("单笔限额"),
                capFirst.run(order, RiskChain.Mode.SHORT_CIRCUIT).failures().stream()
                        .map(f -> ((RiskResult.Rejected) f).rule()).toList());
        assertEquals(List.of("限购"),
                limitFirst.run(order, RiskChain.Mode.SHORT_CIRCUIT).failures().stream()
                        .map(f -> ((RiskResult.Rejected) f).rule()).toList());

        // 两条规则都在链上，size 相同 —— 变的只是顺序
        assertEquals(2, capFirst.size());
        assertEquals(2, limitFirst.size());
    }

    @Test
    @DisplayName("规则知道自己排第几，但不知道自己是谁")
    void ruleKnowsItsIndex() {
        var outcome = RiskChain.standard()
                .run(order("U-BANNED-001", line("SKU-1001", 1, "10.00")));

        var rejected = (RiskResult.Rejected) outcome.failures().getFirst();
        assertEquals(0, rejected.ruleIndex(), "黑名单是链上第 0 条");
    }

    @Test
    @DisplayName("规则之间互不知情 —— 加规则不用改已有规则")
    void rulesAreIndependent() {
        var callLog = new ArrayList<String>();
        var spy = new RiskRule() {
            @Override
            public String name() {
                return "新规则";
            }

            @Override
            public RiskResult check(Order o, RiskContext ctx) {
                callLog.add("跑到了");
                return new RiskResult.Passed();
            }
        };

        // 新规则是自己塞进去的，Blacklist / PurchaseLimit 等一行代码都没改
        RiskChain.standard().with(spy).run(order("U-1001", line("SKU-1001", 1, "10.00")));
        assertEquals(List.of("跑到了"), callLog);
    }

    @Test
    @DisplayName("空链直接拒绝构造")
    void emptyChainRejected() {
        assertThrows(IllegalArgumentException.class, () -> RiskChain.of(List.of()));
    }

    @Test
    @DisplayName("ChainOutcome 的 failures 是不可变的")
    void outcomeIsImmutable() {
        var outcome = RiskChain.standard().run(order("U-BANNED-001", line("X", 1, "1.00")));
        assertThrows(UnsupportedOperationException.class,
                () -> outcome.failures().add(new RiskResult.Passed()));
    }

    @Test
    @DisplayName("Context 反查位置 —— 规则不持有链，但能问自己在第几位")
    void contextLooksUpIndex() {
        var rules = RiskRule.standard();
        var context = new RiskContext(rules, 0);
        var middle = rules.get(2);

        assertEquals(2, context.ruleIndex(middle));
        assertEquals(3, context.remaining(), "context 当前在 #0，总共 4 条");

        // 走到中间那条规则时，它后面还剩 1 条
        assertEquals(1, context.at(2).remaining());
        assertEquals(-1, context.ruleIndex(new RiskRule.PurchaseLimit(1)),
                "不在链上的规则返回 -1");
    }

    }