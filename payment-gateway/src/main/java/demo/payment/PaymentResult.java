package demo.payment;

import java.math.BigDecimal;

/**
 * 支付结果 —— <b>sealed</b>，三种。
 *
 * <p>不用 enum 是因为每种结果携带的数据不同：
 * {@code Success} 有流水号，{@code Declined} 有拒绝码，{@code Error} 有可重试标记。
 *
 * <p>注意 {@code Errored} 里的 {@code retryable} —— 它决定调用方该不该重试，
 * 和 {@code domain-events} 场景里的重试判定是同一个业务问题。
 */
public sealed interface PaymentResult {

    BigDecimal amount();

    /** 成功。 */
    record Success(String transactionId, BigDecimal amount) implements PaymentResult {
        public Success {
            if (transactionId == null || transactionId.isBlank()) {
                throw new IllegalArgumentException("交易流水号不能为空");
            }
        }
    }

    /** 业务拒绝：余额不足、卡已冻结。用户改了参数才可能成功，不该自动重试。 */
    record Declined(String declineCode, String message, BigDecimal amount) implements PaymentResult {}

    /** 系统错误：网关超时。应该重试。 */
    record Errored(String errorCode, boolean retryable, BigDecimal amount) implements PaymentResult {}

    default boolean isSuccess() {
        return this instanceof Success;
    }

    /** 该不该重试。用 sealed 穷尽 switch，不写 default。 */
    default boolean isRetryable() {
        return switch (this) {
            case Success s -> false;           // 成功了重试会重复扣款
            case Declined d -> false;          // 业务拒绝重试没用
            case Errored e -> e.retryable();
        };
    }
}