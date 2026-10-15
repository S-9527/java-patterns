package demo.pipeline;

import java.util.List;

/**
 * 一次流水线运行的报告。
 *
 * <p>模板方法模式最容易忽略的一件事：<b>每一步干了什么要能被看见</b>。
 * 否则出了问题你只知道"导入失败了"，不知道是哪一步、哪一行。
 */
public record StepReport(String step, int inCount, int outCount, List<String> notes) {

    public StepReport {
        notes = List.copyOf(notes);
    }

    public boolean droppedSomething() {
        return outCount < inCount;
    }

    public String describe() {
        var dropped = inCount - outCount;
        var tail = dropped > 0 ? "  ⚠ 丢弃 %d 条".formatted(dropped) : "";
        return "%-10s 入 %-4d 出 %-4d%s".formatted(step, inCount, outCount, tail);
    }
}