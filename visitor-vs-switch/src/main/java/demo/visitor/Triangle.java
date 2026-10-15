package demo.visitor;

import java.util.List;

/** 三角形。 */
public record Triangle(double a, double b, double c) implements Shape {

    public Triangle {
        if (a <= 0 || b <= 0 || c <= 0) {
            throw new IllegalArgumentException("边长必须为正：" + a + ", " + b + ", " + c);
        }
        // 三角不等式
        if (a + b <= c || a + c <= b || b + c <= a) {
            throw new IllegalArgumentException(
                    "不构成三角形：" + a + " + " + b + " + " + c);
        }
    }

    /** 周长。 */
    public double perimeter() {
        return a + b + c;
    }

    /** 面积（海伦公式）。 */
    public double area() {
        var s = perimeter() / 2;
        return Math.sqrt(s * (s - a) * (s - b) * (s - c));
    }

    @Override
    public ShapeResult accept(ShapeVisitor visitor) {
        return visitor.visit(this);
    }
}
