package demo.visitor;

/**
 * 形状 —— <b>sealed 层次结构</b>。
 *
 * <p>这是访问者模式 vs switch 模式匹配的核心对照：
 * <ol>
 *   <li>访问者：每个形状实现 {@code accept(ShapeVisitor)}，双分派</li>
 *   <li>switch：直接 {@code switch (shape)} 做模式匹配，编译器穷举检查</li>
 * </ol>
 *
 * <p><b>sealed 是关键</b>：它让 switch 的穷举检查成为可能。
 * 没有 sealed，switch 的 default 分支就是"未知的未来类型"，编译器无法报警。
 */
public sealed interface Shape permits Circle, Rectangle, Triangle {

    /** 访问者模式的入口 —— 每个形状实现自己的分派，返回计算结果。 */
    ShapeResult accept(ShapeVisitor visitor);
}
