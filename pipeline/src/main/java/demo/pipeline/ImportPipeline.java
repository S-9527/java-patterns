package demo.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板方法：导入流水线的骨架。
 *
 * <p>{@link #templateMethod} 是 {@code final} 的 —— 这不是"怕子类乱改"，
 * 而是<b>把"步骤顺序"这个契约锁死</b>。子类决定每一步<b>做什么</b>，
 * 但不能决定<b>顺序</b>。这是模板方法和策略模式最本质的分界。
 *
 * <p>钩子方法分三类：
 * <ul>
 *   <li>{@link #open()} / {@link #close()} —— 有默认实现，不覆写也能跑（可选步骤）</li>
 *   <li>{@link #readRaw()} / {@link #parse()} —— 必须实现，抽象方法（必选步骤）</li>
 *   <li>{@link #afterParse(List)} —— 有默认实现，但<b>默认实现带 extend 语义</b>，
 *       子类调用 {@code super.afterParse()} 就能在原逻辑上叠加</li>
 * </ul>
 *
 * <p>第三类是最容易设计错的地方。{@code super.xxx()} 写在哪一行，
 * 决定了子类能扩展哪一段 —— 这也是"模板方法 + 继承"最大的调试成本。
 */
public abstract class ImportPipeline {

    /** 报告收集器。每步把自己的结果记一笔。 */
    private final List<StepReport> reports = new ArrayList<>();

    protected ImportPipeline() {
    }

    // ==================== 骨架：final，子类不许改顺序 ====================

    public final List<StepReport> run() {
        reports.clear();

        open();                                   // 1. 可选：建立连接
        var raw = readRaw();                      // 2. 抽象：读原始数据
        report("readRaw", raw.size(), raw.size());

        // 关键：每个步骤是「先占位 report，再执行步骤，最后 repout 回填真实条数」。
        //
        // 为什么要这么绕？因为子类会在步骤内部调 note("validate", "...") 来记录说明。
        // 如果先执行步骤再 report，note() 找不到对应的报告就会扑空。
        // （这个顺序踩过坑，见 note() 的 javadoc。）

        report("parse", raw.size(), raw.size());
        var parsed = parse(raw);                   // 3. 抽象：解析成记录
        repout("parse", parsed.size());

        report("afterParse", parsed.size(), parsed.size());
        var extended = afterParse(parsed);         // 4. 可钩子：子类扩展
        repout("afterParse", extended.size());

        report("validate", extended.size(), extended.size());
        var checked = validate(extended);          // 5. 抽象：校验
        repout("validate", checked.size());

        report("transform", checked.size(), checked.size());
        var transformed = transform(checked);      // 6. 可钩子：子类扩展
        repout("transform", transformed.size());

        write(transformed);                        // 7. 抽象：写出
        // ⚠ write 的报告记的是「真正写出去的条数」，不是传进去的条数。
        // 带 error 的记录会被 write 过滤掉，所以两者不等 —— 这个差别在测试时踩过。
        var written = (int) transformed.stream().filter(SourceRecord::isWritable).count();
        report("write", transformed.size(), written);

        close();                                  // 8. 可选：释放资源
        return List.copyOf(reports);
    }

    private void report(String step, int in, int out) {
        reports.add(new StepReport(step, in, out, List.of()));
    }

    /** 回填某一步的出参条数，保留子类已经写下的 notes。 */
    private void repout(String step, int out) {
        for (int i = reports.size() - 1; i >= 0; i--) {
            var r = reports.get(i);
            if (r.step().equals(step)) {
                reports.set(i, new StepReport(r.step(), r.inCount(), out, r.notes()));
                return;
            }
        }
        throw new IllegalStateException("步骤 %s 没有记录，无法回填".formatted(step));
    }

    // ==================== 必选步骤：子类必须实现 ====================

    /** 读原始行。 */
    protected abstract List<String> readRaw();

    /** 解析成记录。子类实现里<b>不要吞掉解析错误</b>，要返回带 error 的记录。 */
    protected abstract List<SourceRecord> parse(List<String> raw);

    /** 校验，把不合格的行标记出来。 */
    protected abstract List<SourceRecord> validate(List<SourceRecord> records);

    /** 写出。这一步<b>有默认实现</b>，因为"打印到控制台"是合理默认。 */
    protected void write(List<SourceRecord> records) {
        var writable = records.stream().filter(SourceRecord::isWritable).toList();
        System.out.printf("    写出 %d 条有效记录%n", writable.size());
    }

    // ==================== 可选步骤：钩子 ====================

    /** 建立连接。默认什么都不做。 */
    protected void open() {
    }

    /** 释放资源。默认什么都不做。 */
    protected void close() {
    }

    /** 解析后的扩展点。默认原样返回。子类<b>可以</b>覆写它完全替换。 */
    protected List<SourceRecord> afterParse(List<SourceRecord> records) {
        return records;
    }

    /** 校验后的扩展点。默认原样返回。 */
    protected List<SourceRecord> transform(List<SourceRecord> records) {
        return records;
    }

    /**
     * 往报告里追加说明。子类用来记录自己干了什么。
     *
     * <p><b>必须在对应步骤的 report() 之后调用</b>，否则找不到那条报告，
     * 会误插一条 0→0 的占位记录，把步骤序列搞乱 —— 这个 bug 在写测试时踩过。
     */
    protected final void note(String step, String message) {
        for (int i = reports.size() - 1; i >= 0; i--) {
            if (reports.get(i).step().equals(step)) {
                var old = reports.get(i);
                reports.set(i, new StepReport(old.step(), old.inCount(), old.outCount(),
                        concat(old.notes(), message)));
                return;
            }
        }
        throw new IllegalStateException(
                "步骤 %s 还没有记录，无法追加说明 —— note() 必须在对应的 report() 之后调用".formatted(step));
    }

    private static List<String> concat(List<String> a, String b) {
        var next = new ArrayList<>(a);
        next.add(b);
        return next;
    }
}