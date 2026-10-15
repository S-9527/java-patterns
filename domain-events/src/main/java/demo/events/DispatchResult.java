package demo.events;

import java.util.List;

/**
 * 监听结果 —— <b>sealed</b>，两种结局。
 *
 * <p>这一对区分是本场景最重要的一次决策，见 {@link Listener} 的 javadoc：
 * 监听器失败时，是让整个发布失败，还是隔离掉继续跑？两种都有合理场景，
 * 但你必须<b>选一个并说出来</b>，不能糊过去。
 */
public sealed interface DispatchResult {

    /** 监听器成功。 */
    record Ok(String listener, String summary) implements DispatchResult {}

    /** 监听器抛了异常。已被捕获，没有向上传播。 */
    record Failed(String listener, String error, boolean retryable) implements DispatchResult {

        /** 重试建议。短信网关超时该重试，用户已被拉黑不该重试。 */
        public String advice() {
            return retryable ? "应重试" : "重试无用，应告警";
        }
    }

    /**
     * 用 sealed + switch 汇总一批结果。
     *
     * <p>调用方想知道"这次发布整体怎么样"，而这个判断<b>必须</b>把所有结果都看一遍 ——
     * 这正是 sealed 穷尽 switch 的用武之地：编译器保证不会漏掉 {@link Failed}。
     */
    static String summarize(List<DispatchResult> results) {
        var ok = (int) results.stream().filter(r -> r instanceof Ok).count();
        var failed = (int) results.stream().filter(r -> r instanceof Failed).count();
        var retryable = (int) results.stream()
                .filter(r -> r instanceof Failed f && f.retryable()).count();

        return switch (results.isEmpty() ? 0 : (failed > 0 ? 1 : 2)) {
            case 0 -> "没有监听器";
            case 1 -> {
                var detail = results.stream()
                        .filter(r -> r instanceof Failed)
                        .map(r -> ((Failed) r).advice())
                        .distinct()
                        .toList();
                yield "%d 个成功，%d 个失败（%s）".formatted(ok, failed, String.join("、", detail));
            }
            default -> "%d 个全部成功".formatted(ok);
        };
    }

    /** 演示 sealed switch 的穷尽性：任何一类都有明确处置。 */
    static String dispatchAdvice(List<DispatchResult> results) {
        var sb = new StringBuilder();
        for (var r : results) {
            switch (r) {          // 无 default：sealed 保证穷尽
                case Ok(String listener, String summary) ->
                        sb.append("  ✓ %-12s %s%n".formatted(listener, summary));
                case Failed(String listener, String error, boolean retryable) ->
                        sb.append("  ✗ %-12s %s —— %s%n".formatted(listener, error,
                                retryable ? "应重试" : "重试无用，应告警"));
            }
        }
        return sb.toString();
    }
}