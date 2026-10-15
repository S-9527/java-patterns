package demo.risk;

import java.util.List;

/**
 * 规则运行的上下文。
 *
 * <p>{@link #ruleIndex} 这个方法值得单独说：它让每条规则能报出"我是第几条"，
 * 而规则自己并不知道自己在链上的位置。链的编排信息由 {@link RiskChain} 持有，
 * 规则通过 context 反查 —— 这是责任链里控制反向引用的常见手法。
 */
public record RiskContext(List<RiskRule> chain, int currentIndex) {

    public RiskContext {
        chain = List.copyOf(chain);
    }

    /** 我在链上的下标，报错时用得上。 */
    public int ruleIndex(RiskRule self) {
        var index = chain.indexOf(self);
        return index >= 0 ? index : -1;
    }

    public RiskContext at(int index) {
        return new RiskContext(chain, index);
    }

    /** 我后面还有多少条规则没跑。 */
    public int remaining() {
        return chain.size() - currentIndex - 1;
    }
}