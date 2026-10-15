package demo.events;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * 领域事件 —— <b>sealed</b>，三种。
 *
 * <p>sealed 的价值在这里很具体：{@code OrderEventBus} 要把所有事件拼成一行日志，
 * {@code DispatchLog} 要按事件类型分类统计。两处 switch 都靠 sealed 保证穷尽，
 * 将来加第四种事件，编译期就会把所有需要处理它的地方揪出来。
 *
 * <p>不可变是硬要求 —— 事件发布出去可能被很多监听器异步消费，如果它还能被改，
 * 消费方看到的内容就取决于时序了。
 */
public sealed interface OrderEvent {

    String orderId();

    /** 事件发生时刻（字符串便于展示）。 */
    String occurredAt();

    // ---------- 三种事件 ----------

    record OrderPlaced(String orderId, String customerId, int itemCount,
                       String occurredAt) implements OrderEvent {
    }

    record PaymentCaptured(String orderId, BigDecimal amount, Currency currency,
                           String channel, String occurredAt) implements OrderEvent {
    }

    record OrderCancelled(String orderId, String reason,
                          String occurredAt) implements OrderEvent {
    }
}