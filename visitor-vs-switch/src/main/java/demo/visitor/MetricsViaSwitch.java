package demo.visitor;

/**
 * 面积/周长 —— switch 模式匹配写法。
 *
 * <p><b>关键</b>：sealed 接口让编译器穷举检查所有子类型，
 * 不需要 default 分支。漏了一个 case 就是编译错误。
 *
 * <p>这是 Java 17+ 的写法，比 GoF 访问者少了一个接口 + 三个 visit 方法。
 * 逻辑完全一样，代码量减半。
 */
public final class MetricsViaSwitch {

    public static ShapeResult compute(Shape shape) {
        return switch (shape) {
            case Circle c -> {
                var area = Math.PI * c.radius() * c.radius();
                var perimeter = 2 * Math.PI * c.radius();
                yield new ShapeResult(area, perimeter,
                        "圆(r=%s)".formatted(c.radius()));
            }
            case Rectangle r -> {
                var area = r.width() * r.height();
                var perimeter = 2 * (r.width() + r.height());
                yield new ShapeResult(area, perimeter,
                        "矩形(%sx%s)".formatted(r.width(), r.height()));
            }
            case Triangle t -> new ShapeResult(t.area(), t.perimeter(),
                    "三角形(%s,%s,%s)".formatted(t.a(), t.b(), t.c()));
            // ⚠ 没有 default 分支 —— sealed 保证了穷举。
            //   加了 Triangle 之后，编译器会强制所有 switch 补上新 case。
        };
    }
}
