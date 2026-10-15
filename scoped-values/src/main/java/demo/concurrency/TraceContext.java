package demo.concurrency;

/**
 * 请求上下文 —— traceId / customerId / tenant。
 *
 * <p>三种上下文传递方案都要装这一个。
 */
public record TraceContext(String traceId, String customerId, String tenant) {
    @Override
    public String toString() {
        return "TraceContext{trace=%s, customer=%s, tenant=%s}"
                .formatted(traceId, customerId, tenant);
    }
}
