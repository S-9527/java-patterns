package demo.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("建造者 —— 5 必填 + 5 可选，三种写法对比")
class BuilderTest {

    private static List<Item> sampleItems() {
        return List.of(new Item("键盘", new BigDecimal("599"), 1));
    }

    // ─────────────────────────────────────────────
    // 渐进式 Builder
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("渐进式 Builder")
    class ProgressiveBuilder {

        @Test
        @DisplayName("必填字段完整时 build 成功")
        void buildSucceeds() {
            var order = Order.builder()
                    .id("ORD-001")
                    .customerName("张三")
                    .items(sampleItems())
                    .total(new BigDecimal("599"))
                    .build();
            assertEquals("ORD-001", order.id());
            assertEquals("张三", order.customerName());
            assertEquals(OrderStatus.CREATED, order.status(), "默认状态 CREATED");
        }

        @Test
        @DisplayName("⚠ 缺失必填字段时错误信息一次报全")
        void missingRequiredFieldsReportedTogether() {
            var ex = assertThrows(IllegalStateException.class,
                    () -> Order.builder().build());
            assertTrue(ex.getMessage().contains("id"), "应该报 id");
            assertTrue(ex.getMessage().contains("customerName"), "应该报 customerName");
            assertTrue(ex.getMessage().contains("items"), "应该报 items");
            assertTrue(ex.getMessage().contains("total"), "应该报 total");
        }

        @Test
        @DisplayName("⚠ 缺失一个字段时报一个，不连带")
        void singleMissingFieldReported() {
            var ex = assertThrows(IllegalStateException.class,
                    () -> Order.builder()
                            .id("ORD-001")
                            .customerName("张三")
                            .items(sampleItems())
                            // 缺 total
                            .build());
            assertEquals("订单缺少必填字段：[total]", ex.getMessage());
        }

        @Test
        @DisplayName("可选字段不填时用默认值")
        void optionalFieldsDefaulted() {
            var order = Order.builder()
                    .id("ORD-001")
                    .customerName("张三")
                    .items(sampleItems())
                    .total(new BigDecimal("599"))
                    .build();
            assertEquals("", order.couponCode());
            assertEquals("", order.shippingAddress());
            assertEquals("", order.notes());
            assertTrue(order.tags().isEmpty());
            assertEquals(OrderPriority.NORMAL, order.priority());
        }

        @Test
        @DisplayName("渐进式 builder 可以多次 build()")
        void builderIsReusable() {
            var builder = Order.builder()
                    .id("ORD-001")
                    .customerName("张三")
                    .items(sampleItems())
                    .total(new BigDecimal("599"));

            var first = builder.build();
            assertEquals("", first.couponCode());

            builder.couponCode("SAVE10");
            var second = builder.build();
            assertEquals("SAVE10", second.couponCode());

            // builder 状态保留 —— 可以继续改
            builder.notes("加急");
            var third = builder.build();
            assertEquals("SAVE10", third.couponCode(), "couponCode 保留");
            assertEquals("加急", third.notes());
        }

        @Test
        @DisplayName("必填校验在 build() 不在 setter 里")
        void validationOnlyInBuild() {
            // setter 不校验 —— 可以设 null
            var builder = Order.builder();
            builder.id(null);   // 不报错
            builder.total(null); // 不报错
            // 但 build() 会报错
            assertThrows(IllegalStateException.class, builder::build);
        }
    }

    // ─────────────────────────────────────────────
    // toBuilder()
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("toBuilder() —— 局部修改")
    class ToBuilder {

        private static Order sampleOrder() {
            return Order.builder()
                    .id("ORD-001")
                    .customerName("张三")
                    .items(sampleItems())
                    .total(new BigDecimal("599"))
                    .couponCode("SAVE10")
                    .tags(List.of("促销"))
                    .priority(OrderPriority.HIGH)
                    .build();
        }

        @Test
        @DisplayName("toBuilder 保留所有字段")
        void toBuilderPreservesAllFields() {
            var original = sampleOrder();
            var copy = original.toBuilder().build();
            assertEquals(original.id(), copy.id());
            assertEquals(original.customerName(), copy.customerName());
            assertEquals(original.total(), copy.total());
            assertEquals(original.couponCode(), copy.couponCode());
            assertEquals(original.tags(), copy.tags());
            assertEquals(original.priority(), copy.priority());
        }

        @Test
        @DisplayName("toBuilder 后修改单个字段")
        void toBuilderModifySingleField() {
            var original = sampleOrder();
            var modified = original.toBuilder()
                    .status(OrderStatus.PAID)
                    .build();
            assertEquals(OrderStatus.PAID, modified.status());
            assertEquals(original.id(), modified.id(), "其他字段不变");
            assertEquals(original.total(), modified.total(), "其他字段不变");
        }

        @Test
        @DisplayName("toBuilder 往返一致性")
        void toBuilderRoundTrip() {
            var original = sampleOrder();
            var roundTrip = original.toBuilder().build();
            assertEquals(original, roundTrip, "toBuilder → build 往返一致");
        }

        @Test
        @DisplayName("⚠ toBuilder 返回的是新 builder，不是同一个")
        void toBuilderReturnsNewBuilder() {
            var original = sampleOrder();
            var builder1 = original.toBuilder();
            var builder2 = original.toBuilder();
            assertFalse(builder1 == builder2,
                    "⚠ 每次 toBuilder() 返回新实例 —— 改一个不影响另一个");

            builder1.notes("改这个");
            assertEquals("", builder2.build().notes(),
                    "builder2 不受 builder1 影响");
        }
    }

    // ─────────────────────────────────────────────
    // 伸缩构造器
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("伸缩构造器 —— 不用建造者的痛点")
    class Telescoping {

        @Test
        @DisplayName("⚠ 构造器参数多，调用方看不出哪些是必填")
        void telescopingHasManyParams() {
            var ctors = TelescopingOrder.class.getDeclaredConstructors();
            assertEquals(2, ctors.length, "2 个重载构造器");
            assertEquals(5, ctors[0].getParameterCount(), "短构造器 5 个参数");
            assertEquals(10, ctors[1].getParameterCount(), "长构造器 10 个参数");
        }

        @Test
        @DisplayName("短构造器自动填 null")
        void shortConstructorFillsNulls() {
            var order = new TelescopingOrder("ORD-001", "张三",
                    sampleItems(), new BigDecimal("599"), OrderStatus.CREATED);
            assertEquals("", order.couponCode(), "null → 空字符串");
            assertEquals("", order.shippingAddress(), "null → 空字符串");
            assertTrue(order.tags().isEmpty(), "null → 空列表");
            assertEquals(OrderPriority.NORMAL, order.priority(), "null → NORMAL");
        }
    }

    // ─────────────────────────────────────────────
    // 可变 JavaBean
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("可变 JavaBean —— 不用建造者的痛点")
    class Mutable {

        @Test
        @DisplayName("⚠ 没有校验 —— 设了 null 也能 toOrder()")
        void noValidationInSetters() {
            var mutable = new MutableOrder();
            mutable.setId("ORD-001");
            mutable.setCustomerName("张三");
            mutable.setItems(sampleItems());
            mutable.setTotal(new BigDecimal("599"));

            // 可以改任何字段
            mutable.setCustomerName("李四");
            assertEquals("李四", mutable.getCustomerName(),
                    "⚠ 对象可以被改 —— 没有不可变性");
        }

        @Test
        @DisplayName("⚠ 缺失字段时 toOrder() 抛 NPE 而不是有意义的错误")
        void missingFieldThrowsNPE() {
            var mutable = new MutableOrder();
            // 没设 customerName
            mutable.setId("ORD-001");
            mutable.setItems(sampleItems());
            mutable.setTotal(new BigDecimal("599"));

            // toOrder() 调用 new Order(...) 的 compact constructor
            // 会抛 NullPointerException，不是 IllegalStateException
            var ex = assertThrows(NullPointerException.class, mutable::toOrder);
            assertTrue(ex.getMessage().contains("客户名"),
                    "⚠ NPE 而不是有意义的校验错误（消息是中文，不是字段名）");
        }
    }

    // ─────────────────────────────────────────────
    // Order record 本身
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Order record")
    class OrderRecord {

        @Test
        @DisplayName("compact constructor 校验必填字段")
        void compactConstructorValidates() {
            assertThrows(NullPointerException.class,
                    () -> new Order(null, "张三", sampleItems(), BigDecimal.ONE, OrderStatus.CREATED,
                            null, null, null, null, null));
            assertThrows(NullPointerException.class,
                    () -> new Order("ORD-001", null, sampleItems(), BigDecimal.ONE, OrderStatus.CREATED,
                            null, null, null, null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new Order("ORD-001", "张三", List.of(), BigDecimal.ONE, OrderStatus.CREATED,
                            null, null, null, null, null),
                    "空商品列表不合法");
        }

        @Test
        @DisplayName("可选字段 null 时通过访问器返回默认值")
        void optionalFieldsNullSafe() {
            var order = new Order("ORD-001", "张三", sampleItems(), BigDecimal.ONE,
                    OrderStatus.CREATED, null, null, null, null, null);
            assertEquals("", order.couponCode());
            assertEquals("", order.shippingAddress());
            assertEquals("", order.notes());
            assertTrue(order.tags().isEmpty());
            assertEquals(OrderPriority.NORMAL, order.priority());
        }

        @Test
        @DisplayName("⚠ 自定义访问器覆盖了 record 的生成访问器")
        void customAccessorsOverrideGenerated() {
            // record 自动生成的 couponCode() 返回原始值（可能为 null）
            // 自定义的 couponCode() 返回默认值
            // 这个测试验证自定义访问器生效了
            var order = new Order("ORD-001", "张三", sampleItems(), BigDecimal.ONE,
                    OrderStatus.CREATED, null, null, null, null, null);
            // 如果自定义访问器没生效，这里会返回 null 而不是 ""
            assertEquals("", order.couponCode(),
                    "自定义访问器覆盖了 record 的生成访问器");
        }
    }

    // ─────────────────────────────────────────────
    // Item
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Item")
    class ItemTest {

        @Test
        @DisplayName("数量必须为正")
        void itemValidation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Item("键盘", BigDecimal.ONE, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> new Item("键盘", BigDecimal.ONE, -1));
            assertThrows(NullPointerException.class,
                    () -> new Item(null, BigDecimal.ONE, 1));
        }
    }
}
