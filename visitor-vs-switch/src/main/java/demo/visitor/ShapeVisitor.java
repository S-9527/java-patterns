package demo.visitor;

/**
 * 访问者接口 —— GoF 双分派的入口。
 *
 * <p><b>关键设计</b>：每种形状有一个 {@code visit} 方法，
 * 签名带具体形状类型（不是 {@code Shape}），这样才能做类型安全的计算。
 *
 * <p><b>新增形状的代价</b>：
 * <ol>
 *   <li>新形状类（实现 {@link Shape}）</li>
 *   <li>所有访问者都要加一个 {@code visit(NewShape)} 方法</li>
 * </ol>
 * 这就是"开闭原则的代价" —— 对扩展关闭，对修改开放。
 */
public interface ShapeVisitor {

    ShapeResult visit(Circle c);

    ShapeResult visit(Rectangle r);

    ShapeResult visit(Triangle t);
}
