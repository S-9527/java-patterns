package demo.visitor;

/** 圆。 */
public record Circle(double radius) implements Shape {

    public Circle {
        if (radius <= 0) {
            throw new IllegalArgumentException("半径必须为正：" + radius);
        }
    }

    @Override
    public ShapeResult accept(ShapeVisitor visitor) {
        return visitor.visit(this);
    }
}
