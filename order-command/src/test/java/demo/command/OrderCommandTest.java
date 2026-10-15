package demo.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("命令 + 备忘录 —— 可撤销的操作")
class OrderCommandTest {

    private static final BigDecimal TOTAL = new BigDecimal("268.00");

    private static Order newOrder() {
        return new Order("SO-1", TOTAL);
    }

    private static OrderCommand.PlaceOrder place() {
        return new OrderCommand.PlaceOrder("SO-1", TOTAL);
    }

    private static OrderCommand.PayOrder pay() {
        return new OrderCommand.PayOrder("PAY-1", TOTAL);
    }

    private static OrderCommand.ShipOrder ship() {
        return new OrderCommand.ShipOrder("顺丰", "SF1");
    }

    @Test
    @DisplayName("正常执行：下单 → 付款 → 发货")
    void happyPath() {
        var order = newOrder();
        order.execute(place());
        assertEquals(OrderState.OrderStatus.CREATED, order.current().status());

        order.execute(pay());
        assertEquals(OrderState.OrderStatus.PAID, order.current().status());

        order.execute(ship());
        assertEquals(OrderState.OrderStatus.SHIPPED, order.current().status());
    }

    @Test
    @DisplayName("⚠ 只有可撤销的命令才进撤销栈")
    void onlyUndoableCommandsEnterStack() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        assertEquals(2, order.undoDepth());

        order.execute(ship());
        assertEquals(2, order.undoDepth(), "发货不可撤销，没进栈");

        order.execute(new OrderCommand.CancelOrder("不想要了"));
        assertEquals(2, order.undoDepth(), "取消不可撤销，也没进栈");
    }

    @Test
    @DisplayName("⚠ 已发货后撤销付款被跳过 —— 否则状态会倒退")
    void cannotUndoPaymentAfterShipping() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        order.execute(ship());

        // PayOrder.undo() 硬编码回到 CREATED。
        // 如果不管，已发货的订单会被变回待付款 —— 货在路上，钱退了，订单乱了。
        assertFalse(pay().canUndoOn(order.current()), "已发货后付款不可撤销");

        // undo() 会跳过被拒绝的付款，继续撤「下单」
        order.undo();
        assertEquals(OrderState.OrderStatus.CANCELLED, order.current().status(),
                "付款被跳过，实际撤掉的是下单");
        assertEquals(0, order.undoDepth(),
                "付款被 canUndoOn 拒绝后弹出了栈 —— 货已发出，这笔付款不再可撤销");
    }

    @Test
    @DisplayName("⚠ 被跳过的命令会弹出栈 —— 它已经没意义了")
    void blockedCommandIsDroppedFromStack() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        order.execute(ship());

        assertEquals(2, order.undoDepth(), "下单 + 付款");
        // canUndoNow() 看的是栈顶（付款），而付款在已发货状态下不可撤销
        assertFalse(order.canUndoNow(), "栈顶是付款，已发货后不可撤销");

        order.undo();
        assertEquals(0, order.undoDepth(),
                "付款被 canUndoOn 拒绝并弹出；下单被撤销并弹出 —— 栈空了");
        assertFalse(order.canUndoNow());
    }

    @Test
    @DisplayName("⚠ 栈空后再撤销才抛异常")
    void throwsWhenStackEmpty() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        order.execute(ship());

        order.undo();   // 跳过付款，撤销下单
        assertEquals(0, order.undoDepth());

        assertThrows(IllegalStateException.class, order::undo,
                "栈空了，再撤就该明确报错");
    }

    @Test
    @DisplayName("撤销栈是后进先出")
    void undoIsLifo() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());

        assertEquals(OrderState.OrderStatus.PAID, order.current().status());
        order.undo();
        assertEquals(OrderState.OrderStatus.CREATED, order.current().status(),
                "撤的是付款，PAID → CREATED");
    }

    @Test
    @DisplayName("反复合并到栈空")
    void undoUntilEmpty() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());

        assertTrue(order.canUndo());
        order.undo();
        order.undo();

        assertFalse(order.canUndo());
        assertThrows(IllegalStateException.class, order::undo);
    }

    @Test
    @DisplayName("⚠ 反向命令 vs 快照恢复：状态一样，history 不同")
    void reverseCommandKeepsHistory() {
        var a = newOrder();
        a.execute(place());
        a.execute(pay());
        a.undo();                              // 反向命令

        var b = newOrder();
        b.execute(place());
        b.execute(pay());
        var snapshot = b.snapshots().get(2);    // 「执行「付款」前」
        b.restoreTo(snapshot);                 // 快照恢复

        assertEquals(a.current().status(), b.current().status(), "两者最终状态相同");

        // 但 history 不同 —— 这是快照恢复在审计场景下的有损之处
        assertTrue(a.current().history().contains("已付款 → 待付款"),
                "反向命令在 history 里留下了「发生过又撤销了」的痕迹：" + a.current().history());
        assertFalse(b.current().history().contains("已付款 → 待付款"),
                "快照恢复把整个 history 换回去了，撤销痕迹消失：" + b.current().history());
    }

    @Test
    @DisplayName("快照恢复会清空撤销栈 —— 恢复之后不能继续往回撤")
    void restoreClearsUndoStack() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        assertEquals(2, order.undoDepth());

        order.restoreTo(order.snapshots().get(1));
        assertEquals(0, order.undoDepth(),
                "恢复到更早的快照后，比它晚的命令都不能再撤了");
    }

    @Test
    @DisplayName("⚠ 撤销付款要警告用户会丢流水号")
    void undoPaymentWarns() {
        var order = newOrder();
        order.execute(place());

        assertNull(order.nextUndoWarning(), "下单撤销没有代价");

        order.execute(pay());
        assertEquals("撤销付款会丢失交易流水 PAY-1，财务对账需人工确认",
                order.nextUndoWarning());
        assertTrue(order.canUndoNow());

        order.undo();
        assertNull(order.nextUndoWarning(),
                "付款已撤销，栈顶变成下单，没有警告了");

        // ⚠ 但这里有个真实缺陷：再付一次款，警告会原样再出现一次。
        // 可实际上第二次付款被撤销后，财务是能查到两笔记录的，
        // "会丢失流水"这个说法已经不准确了 —— 警告没跟上实际的业务状态。
        order.execute(pay());
        assertEquals("撤销付款会丢失交易流水 PAY-1，财务对账需人工确认",
                order.nextUndoWarning(),
                "⚠ 第二次撤销时警告一模一样，但它已经不准确了 —— 财务实际有两笔记录");
    }

    @Test
    @DisplayName("⚠ Undoable 是单独接口 —— 编译器挡住误用")
    void onlyUndoableCanBeUndone() {
        // 下面是本项目最有说服力的一个"编译错误证明"：
        //
        //   ship() instanceof OrderCommand.Undoable
        //
        // 写出来编译不过 —— 因为 ShipOrder 和 Undoable 类型不重叠，Java 直接拒绝。
        // 同理 ((OrderCommand.Undoable) ship()) 也会编译不过。
        //
        // 所以"可撤销性"不是一个运行时才知道的 flag，而是编译期就定死的事实。
        // 这里用反射来断言同样的事：
        assertTrue(implementsUndoable(OrderCommand.PlaceOrder.class), "下单可撤销");
        assertTrue(implementsUndoable(OrderCommand.PayOrder.class), "付款可撤销");
        assertFalse(implementsUndoable(OrderCommand.ShipOrder.class), "发货不可撤销");
        assertFalse(implementsUndoable(OrderCommand.CancelOrder.class), "取消不可撤销");
    }

    /** 反射版：类是否实现了 Undoable。 */
    private static boolean implementsUndoable(Class<?> type) {
        return java.util.Arrays.stream(type.getInterfaces())
                .anyMatch(i -> i.equals(OrderCommand.Undoable.class));
    }

    @Test
    @DisplayName("⚠ 快照很便宜，因为 OrderState 是不可变 record")
    void snapshotsAreCheapBecauseImmutable() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        order.execute(ship());
        order.undo();          // 撤销付款 → CREATED

        // 快照序列：初始 / 下单前 / 付款前 / 发货前 / 撤销后
        assertEquals(5, order.snapshots().size());

        // get(2) 是「执行「付款」前」：那时订单刚下单完，所以状态是 CREATED
        var beforePayment = order.snapshots().get(2);
        assertEquals(OrderState.OrderStatus.CREATED, beforePayment.status());

        // get(3) 是「执行「发货」前」：那时已付款，状态是 PAID
        var beforeShipping = order.snapshots().get(3);
        assertEquals(OrderState.OrderStatus.PAID, beforeShipping.status());

        // 旧快照不受后续操作影响 —— 不可变性的直接证据
        order.execute(new OrderCommand.CancelOrder("测试"));
        assertEquals(OrderState.OrderStatus.PAID, beforeShipping.status(),
                "旧快照的状态没被后续取消改动 —— 因为 record 不可变");
        assertEquals(OrderState.OrderStatus.CANCELLED, order.current().status(),
                "而当前状态确实变了");
    }

    @Test
    @DisplayName("快照不能为 null")
    void snapshotRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new Snapshot("x", null));
    }

    @Test
    @DisplayName("快照历史记录了每一步，便于审计")
    void snapshotTrailIsComplete() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        order.undo();

        var labels = order.snapshots().stream().map(Snapshot::label).toList();
        assertTrue(labels.contains("初始"));
        assertTrue(labels.stream().anyMatch(l -> l.contains("执行「下单」前")));
        assertTrue(labels.stream().anyMatch(l -> l.contains("执行「付款」前")));
        assertTrue(labels.stream().anyMatch(l -> l.contains("撤销「付款」后")));
    }

    @Test
    @DisplayName("取消订单会记录原因")
    void cancelRecordsReason() {
        var order = newOrder();
        order.execute(place());
        order.execute(new OrderCommand.CancelOrder("用户改主意了"));

        assertEquals(OrderState.OrderStatus.CANCELLED, order.current().status());
        assertTrue(order.current().history().stream()
                        .anyMatch(h -> h.contains("用户改主意了")),
                "原因要留在 history 里：" + order.current().history());
    }

    @Test
    @DisplayName("⚠ 反向命令的历史更完整 —— 这就是生产环境选它的理由")
    void reverseCommandIsBetterForAudit() {
        var order = newOrder();
        order.execute(place());
        order.execute(pay());
        order.undo();

        var history = order.current().history();
        // 反向命令保住了完整轨迹
        assertTrue(history.contains("已付款 → 待付款"),
                "撤销痕迹用可读标签记录，而不是 CREATED 这种枚举名：" + history);
        assertTrue(history.contains("下单 SO-1"));
    }
}