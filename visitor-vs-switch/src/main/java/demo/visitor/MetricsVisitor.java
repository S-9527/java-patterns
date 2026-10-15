package demo.visitor;

/**
 * 面积/周长访问者 —— GoF 双分派写法。
 *
 * <p>每种形状有一个 {@code visit} 方法，访问者通过双分派拿到具体类型。
 */
public final class MetricsVisitor implements ShapeVisitor {

    @Override
    public ShapeResult visit(Circle c) {
        var area = Math.PI * c.radius() * c.radius();
        var perimeter = 2 * Math.PI * c.radius();
        return new ShapeResult(area, perimeter,
                "圆(r=%s)".formatted(c.radius()));
    }

    @Override
    public ShapeResult visit(Rectangle r) {
        var area = r.width() * r.height();
        var perimeter = 2 * (r.width() + r.height());
        return new ShapeResult(area, perimeter,
                "矩形(%sx%s)".formatted(r.width(), r.height()));
    }

    @Override
    public ShapeResult visit(Triangle t) {
        return new ShapeResult(t.area(), t.perimeter(),
                "三角形(%s,%s,%s)".formatted(t.a(), t.b(), t.c()));
    }
}
