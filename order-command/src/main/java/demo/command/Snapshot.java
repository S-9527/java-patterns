package demo.command;

/**
 * 备忘录 —— 一个 {@code record}，装快照。
 *
 * <p>订单状态是不可变 record，所以快照就是"存一个引用"，不需要手写深拷贝。
 * <b>这是 record 带来的实际收益</b>：如果状态是可变对象，备忘录就得多写一堆
 * 复制代码，而且很容易漏掉某个字段 —— 漏了就会出"撤销了一半"的诡异 bug。
 *
 * <p>{@link #describe()} 存在的意义是让快照可读。调试"撤销了三次之后订单是什么状态"
 * 这种问题时，有一份人类可读的快照比有一堆字段省事得多。
 */
public record Snapshot(String label, OrderState state) {

    public Snapshot {
        if (state == null) {
            throw new IllegalArgumentException("快照不能为 null");
        }
    }

    public OrderState.OrderStatus status() {
        return state.status();
    }

    public String describe() {
        return "%s [%s] %s".formatted(label, state.status().label(), state.total());
    }
}