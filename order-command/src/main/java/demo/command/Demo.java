package demo.command;

import java.math.BigDecimal;
import java.util.List;

/**
 * 命令 + 备忘录：可撤销的订单操作。
 *
 * <p>重点是对比两种撤销方式：反向命令 vs 快照恢复。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        heading("哪些命令能撤销");
        out("  下单   → 可撤销（没付款前，取消就是恢复原状）");
        out("  付款   → 可撤销，但会丢流水号");
        out("  发货   → 不可撤销（货已经交给快递了）");
        out("  取消   → 不可撤销（要反悔应该写「恢复」命令，不是给「取消」加 undo）");
        out();
        out("  只有实现 Undoable 的才进撤销栈。");
        out("  ShipOrder 不实现 Undoable —— 想把它当可撤销命令用，编译器直接报错。");

        heading("正常流程 + 撤销栈");
        var order = new Order("SO-3001", new BigDecimal("268.00"));
        executeStep(order, "下单", new OrderCommand.PlaceOrder("SO-3001", new BigDecimal("268.00")));
        executeStep(order, "付款", new OrderCommand.PayOrder("PAY-777", new BigDecimal("268.00")));
        out("  撤销栈深度：%d（下单、付款都可撤销）".formatted(order.undoDepth()));

        executeStep(order, "发货", new OrderCommand.ShipOrder("顺丰", "SF123"));
        out("  撤销栈深度：%d（发货不可撤销，没进栈）".formatted(order.undoDepth()));

        heading("⚠ 反向命令的经典痛点：状态会倒退");
        out("  PayOrder.undo() 写死了「回到待付款」。");
        out("  但订单已经发货了 —— 直接撤销会把已发货的订单变回待付款。");
        out();
        out("  货在路上 + 钱退了 + 订单显示待付款 = 用户会再来下一单。");
        out();
        out("  canUndoOn() 就是被这个逼出来的：");
        out("    PayOrder.canUndoOn(已发货) → false");
        out();
        out("  反向命令模式没法自动解决这件事，每条命令得自己判断当前状态。");

        heading("撤销：跳过被拒绝的命令");
        out("  当前状态：%s，撤销栈深度：%d".formatted(
                order.current().status().label(), order.undoDepth()));
        out("  canUndoNow() = %s（栈顶是付款，已发货后不可撤销）".formatted(order.canUndoNow()));
        out();
        order.undo();
        out("  调用 undo() 之后：");
        out("    当前状态：%s".formatted(order.current().status().label()));
        out("    撤销栈深度：%d".formatted(order.undoDepth()));
        out();
        out("  付款被 canUndoOn 拒绝后弹出了栈 —— 货都发了，这笔付款再没有撤销的意义。");
        out("  被跳过的命令是 pop 掉而不是放回去的。");

        heading("两种撤销方式对比");
        demoTwoUndoStyles();

        heading("⚠ 撤销警告不总是准的");
        demoUndoWarningFlaw();

        heading("快照历史");
        demoSnapshots();

        System.out.println();
    }

    private static void demoUndoWarningFlaw() {
        var order = new Order("SO-3004", new BigDecimal("268.00"));
        order.execute(new OrderCommand.PlaceOrder("SO-3004", new BigDecimal("268.00")));
        order.execute(new OrderCommand.PayOrder("PAY-888", new BigDecimal("268.00")));

        out("  第一次撤销警告：%s".formatted(order.nextUndoWarning()));
        order.undo();
        out("  撤销后警告：%s".formatted(order.nextUndoWarning()));

        order.execute(new OrderCommand.PayOrder("PAY-888", new BigDecimal("268.00")));
        out("  再付一次，警告：%s".formatted(order.nextUndoWarning()));
        out();
        out("  一模一样。但这次其实不准确 —— 财务那边能看到两笔流水。");
        out("  警告写在命令上（无状态），却描述了有状态的业务事实。");
        out("  真实系统应该由备忘录记录「这次撤销丢了什么」，而不是命令自报。");
    }

    private static void demoTwoUndoStyles() {
        // 都只走到付款，不发货（发货后付款不可撤销）
        var a = new Order("SO-3002", new BigDecimal("100.00"));
        a.execute(new OrderCommand.PlaceOrder("SO-3002", new BigDecimal("100.00")));
        a.execute(new OrderCommand.PayOrder("PAY-A", new BigDecimal("100.00")));
        a.undo();                              // 反向命令：撤销付款

        var b = new Order("SO-3002", new BigDecimal("100.00"));
        b.execute(new OrderCommand.PlaceOrder("SO-3002", new BigDecimal("100.00")));
        b.execute(new OrderCommand.PayOrder("PAY-B", new BigDecimal("100.00")));
        b.restoreTo(b.snapshots().get(2));      // 快照恢复：回到「执行「付款」前」

        out("  A 反向命令   undo()          → %s".formatted(a.current().status().label()));
        out("  B 快照恢复   restoreTo(快照) → %s".formatted(b.current().status().label()));
        out();
        out("  状态一样，都是「待付款」。但 history 完全不同：");
        out();
        out("    A（反向命令）");
        a.current().history().forEach(h -> out("      " + h));
        out();
        out("    B（快照恢复）");
        b.current().history().forEach(h -> out("      " + h));
        out();
        out("  反向命令保住了「付款发生过又被撤销」的痕迹 —— 审计时这是关键信息。");
        out("  快照恢复把 history 整个换回去了，痕迹消失，审计时看不出发生过什么。");
        out();
        out("  代价对比：");
        out("    反向命令  语义精确，但每条命令都要手写反向逻辑，还要处理 canUndoOn");
        out("    快照恢复  实现简单，但有损 —— 会悄悄抹掉业务数据");
        out();
        out("  生产环境通常选反向命令，因为审计比实现简单重要。");
    }

    private static void demoSnapshots() {
        // 用一个全新的订单，不复用前面已经撤销过的
        var order = new Order("SO-3003", new BigDecimal("50.00"));
        order.execute(new OrderCommand.PlaceOrder("SO-3003", new BigDecimal("50.00")));
        order.execute(new OrderCommand.PayOrder("PAY-C", new BigDecimal("50.00")));

        out("  备忘录记录了 %d 份快照：".formatted(order.snapshots().size()));
        order.snapshots().forEach(s -> out("    " + s.describe()));
        out();
        out("  快照之所以这么便宜，是因为 OrderState 是不可变 record ——");
        out("  存一个引用就完成了，不需要手写深拷贝。");
        out("  如果 OrderState 是可变的，这里就得多写一堆复制代码，");
        out("  而且很容易漏掉某个字段，漏了就会出「撤销了一半」的诡异 bug。");
        out();
        out("  注意快照 3 记的是「已付款」，而当前状态也是已付款。");
        out("  再执行一条命令，快照 3 的内容不会变 —— 不可变性的直接证据。");
    }

    private static void executeStep(Order order, String label, OrderCommand command) {
        var before = order.current().status().label();
        order.execute(command);
        out("  %-6s %-6s → %s".formatted(label, before, order.current().status().label()));
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}