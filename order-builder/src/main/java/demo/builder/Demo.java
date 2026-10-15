package demo.builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * 建造者：5 个必填 + 5 个可选字段，三种写法对比。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var items = List.of(
                new Item("机械键盘", new BigDecimal("599"), 1),
                new Item("鼠标垫", new BigDecimal("49"), 2)
        );

        heading("伸缩构造器：10 个参数，6 个 null");
        var tele = new TelescopingOrder("ORD-001", "张三", items,
                new BigDecimal("697"), OrderStatus.CREATED);
        out("  %s".formatted(tele));
        out("  ⚠ 调用方看不出哪些是必填 —— 第 5 个参数之后全是 null");
        out("  如果有 10 个可选字段，构造器要写 11 个重载。");

        heading("可变 JavaBean：没有不可变性，没有校验");
        var mutable = new MutableOrder();
        mutable.setId("ORD-002");
        mutable.setCustomerName("李四");
        mutable.setItems(items);
        mutable.setTotal(new BigDecimal("697"));
        out("  %s".formatted(mutable));
        out("  ⚠ 没有校验 —— 忘了 setId 就到用的时候才发现 NPE");
        out("  ⚠ 对象可以被改 —— 测试时容易出残留状态");
        out("  ⚠ 可选字段默认值散落在全局 —— 改一个影响所有");

        heading("渐进式 Builder：必填校验集中在 build()");
        var builder = Order.builder()
                .id("ORD-003")
                .customerName("王五")
                .items(items)
                .total(new BigDecimal("697"))
                .couponCode("SAVE10")
                .shippingAddress("杭州市西湖区")
                .tags(List.of("促销", "新客户"));
        out("  builder 已设置 6 个字段，build() 之前可以重复设置。");

        var order = builder.build();
        out("  %s".formatted(order));
        out();
        out("  ✅ 必填校验集中在 build()，错误信息一次报全：");

        try {
            Order.builder().build();
        } catch (IllegalStateException e) {
            out("     %s".formatted(e.getMessage()));
        }

        heading("渐进式 Builder：同一个 builder 多次 build()");
        var orderWithCoupon = builder.build();
        builder.couponCode(null);
        var orderWithoutCoupon = builder.build();
        out("  有优惠券：%s".formatted(orderWithCoupon.couponCode()));
        out("  无优惠券：%s".formatted(orderWithoutCoupon.couponCode()));
        out("  ✅ builder 不消耗自己 —— 可以生成多个变体。");

        heading("toBuilder()：从已有订单派生");
        var modified = order.toBuilder()
                .status(OrderStatus.PAID)
                .notes("客户要求加急")
                .build();
        out("  原订单：状态 %s，备注 '%s'".formatted(order.status(), order.notes()));
        out("  新订单：状态 %s，备注 '%s'".formatted(modified.status(), modified.notes()));
        out("  ✅ 其他字段保持不变 —— 不用重复设置 6 个字段。");

        heading("可选字段默认值");
        var minimal = Order.builder()
                .id("ORD-004")
                .customerName("赵六")
                .items(items)
                .total(new BigDecimal("697"))
                .build();
        out("  couponCode = '%s'（默认空字符串）".formatted(minimal.couponCode()));
        out("  tags = %s（默认空列表）".formatted(minimal.tags()));
        out("  priority = %s（默认 NORMAL）".formatted(minimal.priority()));
        out();
        out("  注意：Order 的 record 访问器重写了默认值逻辑 ——");
        out("  null 字段通过访问器返回默认值，不暴露 null。");

        System.out.println();
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
