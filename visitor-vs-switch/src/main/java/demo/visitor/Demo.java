package demo.visitor;

import java.util.List;

/**
 * 访问者 vs sealed + switch：两个世界对同一个问题的两种回答。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var shapes = List.of(
                new Circle(2.5),
                new Rectangle(3, 4),
                new Triangle(3, 4, 5)
        );

        heading("访问者写法（GoF 双分派）");
        var visitor = new MetricsVisitor();
        for (var s : shapes) {
            var r = s.accept(visitor);
            out("  %s → 面积 %.2f, 周长 %.2f".formatted(
                    r.description(), r.area(), r.perimeter()));
        }

        heading("switch 写法（sealed + 模式匹配）");
        for (var s : shapes) {
            var r = MetricsViaSwitch.compute(s);
            out("  %s → 面积 %.2f, 周长 %.2f".formatted(
                    r.description(), r.area(), r.perimeter()));
        }

        heading("⚠ 两者结果完全一样");
        var visitorResults = shapes.stream().map(s -> s.accept(visitor)).toList();
        var switchResults = shapes.stream().map(MetricsViaSwitch::compute).toList();

        for (int i = 0; i < shapes.size(); i++) {
            var v = visitorResults.get(i);
            var s = switchResults.get(i);
            out("  %s：等价？ %b".formatted(shapes.get(i), v.isEquivalent(s)));
        }

        heading("代价对比");
        out("  访问者写法：");
        out("    - 新增形状 → 所有访问者都要改（开闭原则的代价）");
        out("    - 新增操作 → 加一个访问者，已有形状不用改（对扩展开放）");
        out("    - 接口数：1（ShapeVisitor）+ 3 个 visit 方法");
        out();
        out("  switch 写法：");
        out("    - 新增形状 → 所有 switch 都要改（编译器强制，漏了编译不过）");
        out("    - 新增操作 → 加一个方法，已有形状不用改");
        out("    - 接口数：0（sealed 保证穷举）");
        out();
        out("  ⚠ 访问者在 Java 里几乎可以退休了 —— sealed + switch 是同一件事的更短写法。");
        out("  唯一还值得用的场景：需要多个独立的访问者，而且它们之间不应该互相知道。");

        System.out.println();
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
    }

    private static void out() {
        System.out.println();
    }

    private static void out(String line) {
        System.out.println(line);
    }
}
