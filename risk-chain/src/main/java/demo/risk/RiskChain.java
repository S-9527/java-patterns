package demo.risk;

import java.util.ArrayList;
import java.util.List;

/**
 * 风控链 —— 责任链。
 *
 * <p><b>关键决策：遇到 {@code Rejected} 不短路，而是把所有规则跑完收集全部问题。</b>
 *
 * <p>教科书责任链通常是"处理或传递给下一个"，命中就停。但下单风控的真实需求往往是
 * "一次性告诉用户所有问题"，否则用户改完黑名单才发现又超限购，来回三四轮体验很差。
 *
 * <p>这个 Demo 用一个 {@link Mode} 参数把两种语义都跑一遍，让你直接对比。
 *
 * <p><b>另一个决策：规则抛出的异常在这里被翻译成 {@link RiskResult.Aborted}。</b>
 * 责任链最容易出的事故是某条规则抛异常，整条链炸掉，连已经知道的问题都丢了。
 * 这里把异常降级成一种结果，让失败信息能汇合。
 */
public final class RiskChain {

    public enum Mode {
        /** 教科书：命中即停（fast fail）。 */
        SHORT_CIRCUIT,
        /** 跑完全部规则，收集所有问题（collect all）。 */
        COLLECT_ALL
    }

    private final List<RiskRule> rules;

    private RiskChain(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** 从规则清单建链。顺序即执行顺序。 */
    public static RiskChain of(List<RiskRule> rules) {
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("链上至少要有一条规则");
        }
        return new RiskChain(rules);
    }

    public static RiskChain standard() {
        return of(RiskRule.standard());
    }

    public List<RiskRule> rules() {
        return rules;
    }

    public RiskChain with(RiskRule rule) {
        var next = new ArrayList<>(rules);
        next.add(rule);
        return new RiskChain(next);
    }

    /** 在指定位置插入，用来演示"规则顺序会改变结果"。 */
    public RiskChain insertingAt(int index, RiskRule rule) {
        var next = new ArrayList<>(rules);
        next.add(index, rule);
        return new RiskChain(next);
    }

    public int size() {
        return rules.size();
    }

    /** 跑一遍链。异常会被翻译成 Aborted。 */
    public RiskResult.ChainOutcome run(Order order) {
        return run(order, Mode.COLLECT_ALL);
    }

    public RiskResult.ChainOutcome run(Order order, Mode mode) {
        var context = new RiskContext(rules, 0);
        var failures = new ArrayList<RiskResult>();

        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            RiskResult result;
            try {
                result = rule.check(order, context.at(i));
            } catch (RuntimeException e) {
                // 降级：不因为单条规则挂掉就丢掉整条链的结论
                result = new RiskResult.Aborted(rule.name(), e.getMessage());
            }

            if (result instanceof RiskResult.Passed) {
                continue;
            }
            failures.add(result);

            // 系统中断永远立即停 —— 后续规则可能依赖它，继续跑结果不可信
            if (result instanceof RiskResult.Aborted) {
                break;
            }
            if (mode == Mode.SHORT_CIRCUIT) {
                break;
            }
        }

        return new RiskResult.ChainOutcome(failures, rules.size());
    }

    /**
     * 反面教材：教科书式的链式 {@code next()} 组装。
     *
     * <p>每条规则持有下一条规则，调用时 {@code rule.handle(ctx).handle(ctx)} 一路传下去。
     * 教科书责任链长这样，本项目<b>不采用</b>，原因见 docs/risk-chain.md：
     *
     * <ol>
     *   <li>每条规则被迫知道"链"的存在 —— 必须多实现一个 {@code next()}，还得维护它</li>
     *   <li>顺序被焊死在代码里，没法从配置/数据库动态组链</li>
     *   <li>想加一条规则要改构造代码，不是加一个类</li>
     * </ol>
     *
     * <p>这里用一个紧凑的 {@code Runnable}-ish 形式复刻它的核心问题：
     * 每条规则都要手动把调用传下去。
     *
     * @return 链尾（终点）之后还能拿到的东西，方便断言"调用确实传到了这里"
     */
    public static List<RiskRule> legacyChainedOrder(List<RiskRule> ordered, List<String> callLog) {
        for (var rule : ordered) {
            // 教科书写法：rule.setNext(nextRule)，然后 handle() 里手写 next.handle()
            // 这里只记录"规则被调用了"，next 的传递关系由调用方负责 —— 正是它的痛点：
            // 漏掉一次 next.handle() 调用，下游就静默不执行，没有编译期检查
            callLog.add(rule.name());
        }
        return ordered;
    }
}