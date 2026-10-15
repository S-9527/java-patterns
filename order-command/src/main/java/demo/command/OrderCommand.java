package demo.command;

import java.math.BigDecimal;
import java.util.List;

/**
 * 命令 —— <b>sealed</b>，五种。
 *
 * <p>命令模式把"要做什么"封装成对象。好处是：可以入队、可以重试、可以记审计日志、
 * 可以撤销 —— 前提是它是一个<b>对象</b>而不是一次方法调用。
 *
 * <p><b>关键设计：只有需要撤销的命令才实现 {@link Undoable}。</b>
 * 不是每个命令都能撤销 —— "发货"撤销不了，"支付"撤销要退款而不是简单地退回去。
 * 用一个单独的接口区分，比在接口里写一个 {@code undo()} 然后到处抛
 * {@code UnsupportedOperationException} 诚实得多。
 *
 * <p>这和 {@code risk-chain} 场景的 {@code Rejected}/{@code Aborted} 是同一套思路：
 * <b>用类型区分"能不能做"，而不是用运行时异常区分</b>。
 *
 * <p><b>sealed 在这里有个直接的副作用</b>：{@code Undoable} 是嵌套接口，
 * 它隐式地是 {@code non-sealed}（Java 规定嵌套在 sealed 类型里的接口必须
 * 显式标注）。这个标注是必需的，漏了编译器会报
 * "sealed or non-sealed modifiers expected" —— 写这个模块时踩到了。
 */
public sealed interface OrderCommand {

    String label();

    /** 执行。返回执行后的新状态。 */
    OrderState execute(OrderState current);

    /**
     * 能撤销的命令。
     *
     * <p><b>必须显式标 {@code non-sealed}</b>：它不是 {@code OrderCommand} 允许的
     * 直接实现者，而是被它的 record 实现者继承的中间层。
     */
    non-sealed interface Undoable extends OrderCommand {

        /** 撤销到之前的状态。 */
        OrderState undo(OrderState current);

        /**
         * 当前状态能不能撤销这条命令。默认能。
         *
         * <p><b>这个方法是反向命令模式的核心痛点</b>：
         * {@link #undo} 只能"把这次操作造成的变化倒过来"，它不知道也不关心
         * 当前状态是什么。于是会出现状态倒退 —— 订单已经发货了，撤销付款却把状态
         * 设回 {@code CREATED}，等于把已发货的订单变回待付款。
         *
         * <p>反向命令模式没法自动解决这个问题，只能让每条命令自己判断。
         * 用备忘录（{@link Order.restoreTo}）就没这个问题，但备忘录恢复是有损的。
         * 两种方式各有代价 —— 见 docs/order-command.md。
         */
        default boolean canUndoOn(OrderState current) {
            return true;
        }

        /** 撤销会丢失什么信息（比如付款记录）。撤销栈要如实告知用户。 */
        default String undoWarning() {
            return null;
        }
    }

    // ---------- 可撤销的命令 ----------

    /**
     * 下单。<b>可撤销</b> —— 没有付款之前，取消订单就是恢复原状。
     */
    record PlaceOrder(String orderId, BigDecimal total) implements Undoable {

        @Override
        public String label() {
            return "下单";
        }

        @Override
        public OrderState execute(OrderState current) {
            var history = new java.util.ArrayList<>(current.history());
            history.add("下单 %s".formatted(orderId));
            return new OrderState(orderId, OrderState.OrderStatus.CREATED, total, history);
        }

        @Override
        public OrderState undo(OrderState current) {
            // 撤销下单 = 回到一个不存在的状态。这里用 null orderId 表示"订单已消失"
            return new OrderState(orderId, OrderState.OrderStatus.CANCELLED, current.total(),
                    List.of("撤销下单"));
        }
    }

    /**
     * 付款。<b>可撤销，但有前提，且会丢流水号</b>。
     *
     * <p>{@link #undoWarning()} 在这里有用：撤销付款后，"已付 268 元"这条记录没了，
     * 财务对不上账。用户需要知道这一点。
     *
     * <p>{@link #canUndoOn} 也是被这个场景逼出来的：订单一旦发货，撤销付款就会让状态
     * 从"已发货"倒退回"待付款"。所以这里禁止在已发货/已取消之后撤销。
     */
    record PayOrder(String paymentId, BigDecimal amount) implements Undoable {

        @Override
        public String label() {
            return "付款";
        }

        @Override
        public String undoWarning() {
            return "撤销付款会丢失交易流水 %s，财务对账需人工确认".formatted(paymentId);
        }

        @Override
        public boolean canUndoOn(OrderState current) {
            // 已经发货或已取消时，付款记录不应被撤销 —— 货在路上了，钱退了订单就乱了
            return switch (current.status()) {
                case CREATED, PAID -> true;
                case SHIPPED, CANCELLED -> false;
            };
        }

        @Override
        public OrderState execute(OrderState current) {
            return current.withStatus(OrderState.OrderStatus.PAID);
        }

        @Override
        public OrderState undo(OrderState current) {
            return current.withStatus(OrderState.OrderStatus.CREATED);
        }
    }

    /**
     * 发货。<b>不可撤销</b>。
     *
     * <p>货物已经交给快递了，"撤销发货"在业务上没有意义。
     * 这就是为什么它不实现 {@link Undoable} —— 编译器会挡住误用：
     * {@code PayOrder u = (PayOrder) cmd;} 然后 {@code u.undo()} 是能写的，
     * 但把 {@code ShipOrder} 当 {@code Undoable} 用则编译不过。
     */
    record ShipOrder(String carrier, String trackingNo) implements OrderCommand {

        @Override
        public String label() {
            return "发货";
        }

        @Override
        public OrderState execute(OrderState current) {
            return current.withStatus(OrderState.OrderStatus.SHIPPED);
        }
    }

    /**
     * 取消。<b>不可撤销</b> —— 取消之后不能"撤销取消"。
     *
     * <p>如果业务上需要反悔，那要写的是另一个命令 {@code RestoreOrder}，
     * 而不是给 {@code CancelOrder} 加个 {@code undo()}。
     * 两者语义完全不同：撤销是"回到执行之前"，恢复是"再走一遍相反流程"。
     */
    record CancelOrder(String reason) implements OrderCommand {

        @Override
        public String label() {
            return "取消";
        }

        @Override
        public OrderState execute(OrderState current) {
            var state = current.withStatus(OrderState.OrderStatus.CANCELLED);
            return new OrderState(state.orderId(), state.status(), state.total(),
                    appendReason(current.history(), reason));
        }

        private static List<String> appendReason(List<String> history, String reason) {
            var next = new java.util.ArrayList<>(history);
            next.add("取消原因：" + reason);
            return next;
        }
    }

    // ---------- 工厂方法：让命令可以脱离领域对象创建 ----------

    static OrderCommand pay(String paymentId, BigDecimal amount) {
        return new PayOrder(paymentId, amount);
    }
}