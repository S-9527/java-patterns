package demo.visitor;

/**
 * 形状计算结果 —— 面积 + 周长 + 描述。
 *
 * <p>用 record 是为了让访问者和 switch 两种写法返回同一个类型，
 * 这样等价性测试可以直接比较。
 */
public record ShapeResult(double area, double perimeter, String description) {

    /** 两个结果是否等价（浮点数用 1e-9 容差）。 */
    public boolean isEquivalent(ShapeResult other) {
        return Math.abs(this.area - other.area) < 1e-9
                && Math.abs(this.perimeter - other.perimeter) < 1e-9
                && this.description.equals(other.description);
    }
}
