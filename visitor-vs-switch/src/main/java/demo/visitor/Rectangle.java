package demo.visitor;

/** 矩形。 */
public record Rectangle(double width, double height) implements Shape {

    public Rectangle {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("宽和高必须为正：" + width + " x " + height);
        }
    }

    @Override
    public ShapeResult accept(ShapeVisitor visitor) {
        return visitor.visit(this);
    }
}
