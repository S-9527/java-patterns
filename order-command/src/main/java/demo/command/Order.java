package demo.command;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 订单 —— 命令的执行者 + 撤销栈的管理者。
 *
 * <p>每条命令执行前，{@link #execute} 会自动压一份快照。这是备忘录和命令模式的结合点：
 * 命令负责"做什么"，宿主负责"备份和恢复"。
 *
 * <h2>两种撤销方式，本项目都实现了</h2>
 *
 * <ul>
 *   <li>{@link #undo()} —— <b>反向命令</b>：调用 {@code command.undo(current)}。
 *       语义精确，但要求每条可撤销命令都手写反向逻辑。</li>
 *   <li>{@link #restoreTo(Snapshot)} —— <b>快照恢复</b>：直接把状态换回快照。
 *       实现简单，但<b>会丢失快照之后的所有信息</b>（比如付款流水号）。</li>
 * </ul>
 *
 * <p>两种方式的区别在 {@code OrderCommandTest} 里被明确断言了。
 * 生产环境通常用反向命令，因为快照恢复会悄悄抹掉业务数据。
 */
public final class Order {

    private final List<Snapshot> snapshots = new ArrayList<>();
    private final Deque<Executed> undoStack = new ArrayDeque<>();

    private OrderState current;

    public Order(String orderId, BigDecimal total) {
        this.current = new OrderState(orderId, OrderState.OrderStatus.CREATED, total,
                List.of("初始化"));
        snapshots.add(new Snapshot("初始", current));
    }

    /** 执行一条命令。快照和入栈都是自动的。 */
    public OrderState execute(OrderCommand command) {
        // 1. 先备份 —— 撤销的依据
        var before = new Snapshot("执行「%s」前".formatted(command.label()), current);
        snapshots.add(before);

        // 2. 执行
        current = command.execute(current);

        // 3. 只有可撤销的才进撤销栈
        //    ShipOrder / CancelOrder 不实现 Undoable，编译器挡住它们被误撤销
        if (command instanceof OrderCommand.Undoable undoable) {
            undoStack.push(new Executed(undoable, before));
        }
        return current;
    }

    /** 撤销：走<b>反向命令</b>。 */
    public OrderState undo() {
        if (undoStack.isEmpty()) {
            throw new IllegalStateException(
                    "没有可撤销的操作（已执行 %d 条命令，其中 %d 条不可撤销）"
                            .formatted(snapshots.size() - 1, snapshots.size() - 1 - undoStack.size()));
        }

        // 栈是 LIFO。栈顶的命令如果当前状态不允许撤销，就继续往下找。
        // ⚠ 反向命令模式做不到自动判断 —— 每条命令得自己实现 canUndoOn()
        var blocked = new ArrayList<String>();
        while (!undoStack.isEmpty()) {
            var candidate = undoStack.peek();
            if (!candidate.command().canUndoOn(current)) {
                blocked.add(candidate.command().label());
                undoStack.pop();      // 跳过去，不放回栈里 —— 它已经没意义了
                continue;
            }
            current = candidate.command().undo(current);
            undoStack.pop();
            snapshots.add(new Snapshot("撤销「%s」后".formatted(candidate.command().label()),
                    current));
            return current;
        }

        throw new IllegalStateException(
                "栈里 %d 条可撤销命令在当前状态（%s）下都不能撤销：%s"
                        .formatted(blocked.size(), current.status().label(), blocked));
    }

    /** 撤销：走<b>快照恢复</b>。直接换状态，不问命令。 */
    public OrderState restoreTo(Snapshot snapshot) {
        current = snapshot.state();
        snapshots.add(new Snapshot("恢复到「%s」".formatted(snapshot.label()), current));
        // 恢复之后，栈里比这个快照更晚的命令都不能再撤销了
        undoStack.clear();
        return current;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public int undoDepth() {
        return undoStack.size();
    }

    public OrderState current() {
        return current;
    }

    /** 快照历史，供审计和调试。 */
    public List<Snapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    /**
     * 下一次撤销会丢什么。
     *
     * <p>{@code PayOrder} 的 {@code undoWarning()} 在这里冒出来 ——
     * 撤销付款会丢流水号。用户必须被告知，而不是被悄悄抹掉。
     *
     * <p>注意这里有个<b>真实的缺陷</b>：{@code undoWarning()} 是写在命令上的，
     * 但"撤销付款会丢流水号"这件事其实只在<b>第一次</b>撤销时成立。
     * 连撤两次（付款→撤销→再付款→再撤销）之后，财务那边其实还能查到两笔记录，
     * 警告就不准确了。真实系统应该由备忘录来记录"这次撤销丢了什么"。
     */
    public String nextUndoWarning() {
        return undoStack.isEmpty() ? null : undoStack.peek().command().undoWarning();
    }

    /** 当前状态下，下一次撤销是否可行。 */
    public boolean canUndoNow() {
        return !undoStack.isEmpty() && undoStack.peek().command().canUndoOn(current);
    }

    /** 撤销栈的一项：命令 + 它执行前的快照。 */
    private record Executed(OrderCommand.Undoable command, Snapshot before) {
    }
}