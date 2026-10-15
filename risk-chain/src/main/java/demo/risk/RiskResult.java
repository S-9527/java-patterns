package demo.risk;

import demo.risk.RiskResult.Rejected;

import java.util.List;

/**
 * 检查结果 —— <b>sealed</b> 的四种结局。
 *
 * <p>用 sealed 而不是 enum，是因为每种结局携带的数据不一样。
 * enum 做不到 {@code Rejected(rule, reason, ruleIndex)} 这种带结构的载荷。
 *
 * <p>sealed 在这里的直接收益：{@link #describe()} 里的 switch 不写 default，
 * 编译器保证穷尽。将来加一种结局，所有 switch 到它的地方都会编译不过。
 *
 * <p>注意 {@code permits} 列了 {@code ChainOutcome} —— 它本身也是 {@code RiskResult}。
 * 这带来一个必须小心的点：{@code switch (result)} 现在要处理 4 种情况而不是 3 种。
 * 这正是 sealed 穷尽性检查在起作用，不是麻烦。
 */
public sealed interface RiskResult permits RiskResult.Passed, RiskResult.Rejected,
        RiskResult.Aborted, RiskResult.ChainOutcome {

    /** 通过，继续下一条规则。 */
    record Passed() implements RiskResult {}

    /** 业务拒绝：用户明确不该下单（黑名单、超限购）。提示用户。 */
    record Rejected(String rule, String reason, int ruleIndex) implements RiskResult {}

    /**
     * 系统中断：风控服务挂了、库存系统超时。
     *
     * <p>单独区分它和 {@link Rejected} 是有实际意义的 —— 两者的重试策略完全不同：
     * 业务拒绝重试一万次还是被拒；系统中断应该重试或降级。
     * 把两者混成一个"失败"，就丢掉了这个信息。
     */
    record Aborted(String rule, String cause) implements RiskResult {}

    default boolean isPassed() {
        return this instanceof Passed;
    }

    /** 人类可读的一行结论。sealed 穷尽，不写 default。 */
    default String describe() {
        return switch (this) {
            case Passed p -> "通过";
            case Rejected(String rule, String reason, int idx) ->
                    "拒绝（规则 #%d %s：%s）".formatted(idx, rule, reason);
            case Aborted(String rule, String cause) -> "中断（%s：%s）".formatted(rule, cause);
            case ChainOutcome co -> co.describe();
        };
    }

    /**
     * 把一条链跑完的结果收拢。
     *
     * <p>注意链上可能有<b>多条</b>规则同时报错（比如既超限购又库存不足），
     * 所以这里收集成 List 而不是单个值。是否要把所有问题一次性告诉用户，
     * 是个产品决策。
     */
    record ChainOutcome(List<RiskResult> failures, int ruleCount) implements RiskResult {

        public ChainOutcome {
            failures = List.copyOf(failures);
        }

        @Override
        public String describe() {
            return failures.isEmpty()
                    ? "全部 %d 条规则通过".formatted(ruleCount)
                    : "%d 条规则未通过：%s".formatted(failures.size(),
                            failures.stream().map(RiskResult::describe).toList());
        }
    }

    static Rejected reject(String rule, String reason, int index) {
        return new Rejected(rule, reason, index);
    }
}