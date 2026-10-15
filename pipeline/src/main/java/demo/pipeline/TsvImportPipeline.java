package demo.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * TSV 导入 —— <b>故意不覆写任何钩子</b>，用来说明钩子的默认值是可用的。
 *
 * <p>它只实现 3 个抽象方法就完整跑通了 8 步流水线。
 * 这是模板方法相比策略模式的实际优势：<b>新加一个步骤不需要每个子类都改</b>。
 * 如果 {@code afterValidate} 是策略对象，TSV 也要被迫提供一个"什么都不做"的实现。
 */
public final class TsvImportPipeline extends ImportPipeline {

    private final List<String> source;

    public TsvImportPipeline(List<String> source) {
        this.source = List.copyOf(source);
    }

    @Override
    protected List<String> readRaw() {
        return source;
    }

    @Override
    protected List<SourceRecord> parse(List<String> raw) {
        var result = new ArrayList<SourceRecord>();
        for (var line : raw) {
            if (line.isBlank()) {
                continue;
            }
            var cols = line.split("\t");
            if (cols.length < 3) {
                result.add(SourceRecord.invalid(line, "列数不足 3"));
                continue;
            }
            try {
                // 第 4 列可有可无，缺省为 UNKNOWN
                var category = cols.length > 3 ? cols[3].trim() : "UNKNOWN";
                result.add(new SourceRecord(line, cols[0].trim(),
                        Integer.parseInt(cols[1].trim()),
                        new BigDecimal(cols[2].trim()), category, null));
            } catch (NumberFormatException e) {
                result.add(SourceRecord.invalid(line, "解析失败：" + e.getMessage()));
            }
        }
        return result;
    }

    @Override
    protected List<SourceRecord> validate(List<SourceRecord> records) {
        // TSV 的校验比 CSV 宽松：只查数量，不查价格符号
        var result = new ArrayList<SourceRecord>();
        for (var r : records) {
            if (!r.isValid()) {
                result.add(r);
            } else if (r.quantity() <= 0) {
                result.add(SourceRecord.invalid(r.rawLine(), "数量必须为正"));
            } else {
                result.add(r);
            }
        }
        return result;
    }
}