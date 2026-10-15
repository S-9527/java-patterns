package demo.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("访问者 vs sealed+switch —— 等价性测试")
class VisitorTest {

    // ─────────────────────────────────────────────
    // 核心：等价性
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("⚠ 等价性 —— 访问者和 switch 对同一输入产生同一结果")
    class Equivalence {

        @Test
        @DisplayName("圆：访问者结果 == switch 结果")
        void circleEquivalence() {
            var shape = new Circle(2.5);
            var visitorResult = shape.accept(new MetricsVisitor());
            var switchResult = MetricsViaSwitch.compute(shape);

            assertTrue(visitorResult.isEquivalent(switchResult),
                    "⚠ 两种写法对同一个圆产生同一个结果");
            assertEquals(Math.PI * 2.5 * 2.5, visitorResult.area(), 1e-9);
            assertEquals(2 * Math.PI * 2.5, visitorResult.perimeter(), 1e-9);
        }

        @Test
        @DisplayName("矩形：访问者结果 == switch 结果")
        void rectangleEquivalence() {
            var shape = new Rectangle(3, 4);
            var visitorResult = shape.accept(new MetricsVisitor());
            var switchResult = MetricsViaSwitch.compute(shape);

            assertTrue(visitorResult.isEquivalent(switchResult),
                    "⚠ 两种写法对同一个矩形产生同一个结果");
            assertEquals(12.0, visitorResult.area(), 1e-9);
            assertEquals(14.0, visitorResult.perimeter(), 1e-9);
        }

        @Test
        @DisplayName("三角形：访问者结果 == switch 结果")
        void triangleEquivalence() {
            var shape = new Triangle(3, 4, 5);
            var visitorResult = shape.accept(new MetricsVisitor());
            var switchResult = MetricsViaSwitch.compute(shape);

            assertTrue(visitorResult.isEquivalent(switchResult),
                    "⚠ 两种写法对同一个三角形产生同一个结果");
            assertEquals(6.0, visitorResult.area(), 1e-9, "3-4-5 直角三角形面积");
            assertEquals(12.0, visitorResult.perimeter(), 1e-9);
        }

        @Test
        @DisplayName("⚠ 批量等价性：100 个随机形状，两种写法结果完全一致")
        void bulkEquivalence() {
            var shapes = generateShapes(100);
            var visitor = new MetricsVisitor();

            for (var shape : shapes) {
                var visitorResult = shape.accept(visitor);
                var switchResult = MetricsViaSwitch.compute(shape);
                assertTrue(visitorResult.isEquivalent(switchResult),
                        "⚠ 对形状 %s，两种写法结果不一致".formatted(shape));
            }
        }

        @Test
        @DisplayName("⚠ 浮点数精度：等价性用 1e-9 容差")
        void floatingPointTolerance() {
            // 大数运算可能有精度差异
            var bigCircle = new Circle(1_000_000);
            var visitorResult = bigCircle.accept(new MetricsVisitor());
            var switchResult = MetricsViaSwitch.compute(bigCircle);

            // 两者都算 Math.PI * r^2，所以应该完全一致
            assertTrue(visitorResult.isEquivalent(switchResult),
                    "大数圆的面积和周长都应该一致");
            assertTrue(Math.abs(visitorResult.area() - switchResult.area()) < 1e-6,
                    "面积差应该在 1e-6 以内");
        }
    }

    // ─────────────────────────────────────────────
    // 访问者模式
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("访问者模式")
    class VisitorPattern {

        @Test
        @DisplayName("每个形状都实现了 accept")
        void shapesImplementAccept() {
            var shapes = List.of(
                    new Circle(1.0),
                    new Rectangle(2, 3),
                    new Triangle(3, 4, 5)
            );
            for (var shape : shapes) {
                var result = shape.accept(new MetricsVisitor());
                assertTrue(result.area() > 0);
                assertTrue(result.perimeter() > 0);
            }
        }

        @Test
        @DisplayName("⚠ 新增形状后，所有访问者都要加 visit 方法")
        void newShapeRequiresNewVisitMethod() {
            // 这个测试验证 ShapeVisitor 接口有 3 个 visit 方法
            // 如果新增 Square，就必须在这里加 visit(Square)
            var visitor = new MetricsVisitor();
            var methods = java.util.Arrays.stream(ShapeVisitor.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();
            assertEquals(3, methods.size(),
                    "⚠ ShapeVisitor 有 3 个 visit 方法（对应 3 种形状）");
            assertTrue(methods.contains("visit"));
        }

        @Test
        @DisplayName("访问者可以做不同的计算")
        void visitorCanComputeDifferentThings() {
            // 用一个简单的「描述访问者」
            ShapeVisitor descVisitor = new ShapeVisitor() {
                @Override
                public ShapeResult visit(Circle c) {
                    return new ShapeResult(0, 0, "圆，半径 %s".formatted(c.radius()));
                }
                @Override
                public ShapeResult visit(Rectangle r) {
                    return new ShapeResult(0, 0,
                            "矩形，%sx%s".formatted(r.width(), r.height()));
                }
                @Override
                public ShapeResult visit(Triangle t) {
                    return new ShapeResult(0, 0,
                            "三角形，边长 %s,%s,%s".formatted(t.a(), t.b(), t.c()));
                }
            };

            var desc = new Circle(5).accept(descVisitor);
            assertTrue(desc.description().contains("圆"));
        }
    }

    // ─────────────────────────────────────────────
    // switch 模式匹配
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("switch 模式匹配")
    class SwitchPattern {

        @Test
        @DisplayName("switch 处理所有形状类型")
        void switchHandlesAllShapes() {
            var shapes = List.of(
                    new Circle(1.0),
                    new Rectangle(2, 3),
                    new Triangle(3, 4, 5)
            );
            for (var shape : shapes) {
                var result = MetricsViaSwitch.compute(shape);
                assertTrue(result.area() > 0);
                assertTrue(result.perimeter() > 0);
            }
        }

        @Test
        @DisplayName("⚠ sealed 保证穷举 —— 没有 default 分支")
        void sealedGuaranteesExhaustiveness() {
            // 这个测试无法直接断言"没有 default"，
            // 但可以通过编译检查间接验证：如果 Shape 不是 sealed，
            // 编译器不会强制补全所有 case。
            // 这里验证 sealed 接口有 3 个 permits
            var permits = java.util.Arrays.stream(Shape.class.getPermittedSubclasses())
                    .map(Class::getSimpleName)
                    .toList();
            assertEquals(3, permits.size(), "⚠ Shape 有 3 个 permits（Circle, Rectangle, Triangle）");
            assertTrue(permits.containsAll(List.of("Circle", "Rectangle", "Triangle")));
        }

        @Test
        @DisplayName("switch 的编译器穷举检查：漏了 case 就是编译错误")
        void switchExhaustivenessIsEnforcedAtCompileTime() {
            // 这个测试间接验证了：如果 MetricsViaSwitch.compute 漏了某个 case，
            // 编译器会报错。我们无法在运行时测试编译错误，
            // 但可以在运行时验证：所有形状都能被正确处理。
            var shapes = List.of(
                    new Circle(1.0),
                    new Rectangle(2, 3),
                    new Triangle(3, 4, 5)
            );
            for (var shape : shapes) {
                // 如果 switch 漏了某个 case，这里会抛 MatchException
                var result = MetricsViaSwitch.compute(shape);
                assertTrue(result.description().contains("圆")
                        || result.description().contains("矩形")
                        || result.description().contains("三角形"),
                        "switch 对所有形状都有处理");
            }
        }
    }

    // ─────────────────────────────────────────────
    // 代价对比
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("代价对比")
    class CostComparison {

        @Test
        @DisplayName("访问者：新增形状 → 所有访问者都要改")
        void visitorCostForNewShape() {
            // 访问者接口有 3 个 visit 方法（对应 3 种形状）
            // 新增第 4 种形状（如 Square），就必须加 visit(Square)
            var visitorMethodCount = java.util.Arrays.stream(
                    ShapeVisitor.class.getDeclaredMethods()).count();
            assertEquals(3, visitorMethodCount,
                    "⚠ 新增形状的成本：ShapeVisitor 接口要加一个 visit 方法");
        }

        @Test
        @DisplayName("switch：新增形状 → 所有 switch 都要改（编译器强制）")
        void switchCostForNewShape() {
            // switch 的成本和访问者一样：新增形状要改所有 switch
            // 但区别是：编译器会强制你改，漏了编译不过
            // 访问者不会强制 —— 你忘了加 visit(Square) 也能编译通过，运行时才报错
            var permits = Shape.class.getPermittedSubclasses();
            assertEquals(3, permits.length,
                    "⚠ 新增形状的成本：所有 switch 都要加新 case（编译器强制）");
        }

        @Test
        @DisplayName("访问者：新增操作 → 加一个访问者，已有形状不用改")
        void visitorBenefitForNewOperation() {
            // 访问者的优势：新增操作只加一个访问者，已有形状不用改
            // 这就是"对扩展开放"的那一面
            ShapeVisitor weightVisitor = new ShapeVisitor() {
                @Override
                public ShapeResult visit(Circle c) {
                    return new ShapeResult(Math.PI * c.radius() * c.radius(),
                            2 * Math.PI * c.radius(), "圆重量");
                }
                @Override
                public ShapeResult visit(Rectangle r) {
                    return new ShapeResult(r.width() * r.height(),
                            2 * (r.width() + r.height()), "矩形重量");
                }
                @Override
                public ShapeResult visit(Triangle t) {
                    return new ShapeResult(t.area(), t.perimeter(), "三角形重量");
                }
            };

            // 已有形状不需要任何改动
            var result = new Circle(3).accept(weightVisitor);
            assertEquals("圆重量", result.description());
        }

        @Test
        @DisplayName("⚠ 访问者在 Java 里的真正代价：接口膨胀")
        void visitorInterfaceInflation() {
            // 访问者接口的方法数 = 形状类型数
            // 10 种形状 → 10 个 visit 方法
            // 访问者多了，每个新形状要改的接口数 = 访问者数 × 形状数
            var visitorMethods = ShapeVisitor.class.getDeclaredMethods().length;
            var shapeCount = Shape.class.getPermittedSubclasses().length;
            assertEquals(shapeCount, visitorMethods,
                    "⚠ 访问者接口的方法数 = 形状数。10 种形状 + 5 种访问者 = 50 个方法");
        }
    }

    // ─────────────────────────────────────────────
    // 输入校验
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("输入校验")
    class Validation {

        @Test
        @DisplayName("Circle 半径必须为正")
        void circleValidation() {
            assertThrows(IllegalArgumentException.class, () -> new Circle(0));
            assertThrows(IllegalArgumentException.class, () -> new Circle(-1));
        }

        @Test
        @DisplayName("Rectangle 宽和高必须为正")
        void rectangleValidation() {
            assertThrows(IllegalArgumentException.class, () -> new Rectangle(0, 3));
            assertThrows(IllegalArgumentException.class, () -> new Rectangle(3, 0));
            assertThrows(IllegalArgumentException.class, () -> new Rectangle(-1, 3));
        }

        @Test
        @DisplayName("Triangle 边长必须为正且满足三角不等式")
        void triangleValidation() {
            assertThrows(IllegalArgumentException.class, () -> new Triangle(0, 3, 4),
                    "边长为 0，不合法");
            assertThrows(IllegalArgumentException.class, () -> new Triangle(-1, 3, 4),
                    "边长为负，不合法");
            // 3-4-5 是合法的直角三角形
            var valid = new Triangle(3, 4, 5);
            assertTrue(valid.area() > 0);
        }

        @Test
        @DisplayName("Triangle 不满足三角不等式时抛异常")
        void triangleViolatesInequality() {
            assertThrows(IllegalArgumentException.class, () -> new Triangle(1, 1, 5),
                    "1+1 < 5，不合法");
            assertThrows(IllegalArgumentException.class, () -> new Triangle(1, 2, 5),
                    "1+2 < 5，不合法");
            assertThrows(IllegalArgumentException.class, () -> new Triangle(0, 3, 4),
                    "边长为 0，不合法");
        }

        @Test
        @DisplayName("ShapeResult 等价性")
        void shapeResultEquivalence() {
            var a = new ShapeResult(1.0, 2.0, "圆");
            var b = new ShapeResult(1.0, 2.0, "圆");
            assertTrue(a.isEquivalent(b));

            var c = new ShapeResult(1.0, 2.0, "矩形");
            assertFalse(a.isEquivalent(c), "描述不同，不等价");

            var d = new ShapeResult(1.0 + 1e-10, 2.0, "圆");
            assertTrue(a.isEquivalent(d), "1e-10 精度差异在容差内");

            var e = new ShapeResult(1.0 + 1e-8, 2.0, "圆");
            assertFalse(a.isEquivalent(e), "1e-8 精度差异超出容差");
        }
    }

    // ─────────────────────────────────────────────
    // 辅助
    // ─────────────────────────────────────────────

    private static List<Shape> generateShapes(int count) {
        var shapes = new java.util.ArrayList<Shape>(count);
        var random = new java.util.Random(42); // 固定种子，结果可复现
        for (int i = 0; i < count; i++) {
            switch (i % 3) {
                case 0 -> shapes.add(new Circle(0.1 + random.nextDouble() * 10));
                case 1 -> shapes.add(new Rectangle(0.1 + random.nextDouble() * 10,
                        0.1 + random.nextDouble() * 10));
                default -> {
                    // 生成一个合法的三角形：c 必须在 (|a-b|, a+b) 之间
                    var a = 1.0 + random.nextDouble() * 5;
                    var b = 1.0 + random.nextDouble() * 5;
                    var lower = Math.abs(a - b) + 0.1;
                    var upper = a + b - 0.1;
                    var c = lower + random.nextDouble() * (upper - lower);
                    shapes.add(new Triangle(a, b, c));
                }
            }
        }
        return shapes;
    }
}
