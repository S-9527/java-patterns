package demo.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 导入 —— 覆写了所有必选步骤，用上两个钩子。
 *
 * <p>注意 {@link #parse} 的返回值约定：解析失败的行**不会消失**，
 * 而是变成带 {@code error} 的 {@link SourceRecord}。这让最终报告能说清
 * "1000 行里 3 行坏了"，而不是只说"导入了 997 行"。
 */
public final class CsvImportPipeline extends ImportPipeline {

    private final List<String> source;

    public CsvImportPipeline(List<String> source) {
        this.source = List.copyOf(source);
    }

    @Override
    protected void open() {
        // 注意 open() 在骨架的 readRaw report() 之前跑，所以这里不能 note()
        System.out.println("    open: 准备读取 CSV");
    }

    @Override
    protected List<String> readRaw() {
        return source;
    }

    @Override
    protected List<SourceRecord> parse(List<String> raw) {
        var result = new ArrayList<SourceRecord>();
        for (var line : raw) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;   // 空行和注释行直接跳过，不算错误
            }
            var cols = line.split(",");
            if (cols.length != 4) {
                result.add(SourceRecord.invalid(line,
                        "列数不对，期望 4 实际 " + cols.length));
                continue;
            }
            try {
                result.add(new SourceRecord(line,
                        cols[0].trim(),
                        Integer.parseInt(cols[1].trim()),
                        new BigDecimal(cols[2].trim()),
                        cols[3].trim(),
                        null));
            } catch (NumberFormatException e) {
                // 吞掉异常是对的，但必须留下 error 标记，不能静默丢行
                result.add(SourceRecord.invalid(line, "数字解析失败：" + e.getMessage()));
            }
        }
        return result;
    }

    @Override
    protected List<SourceRecord> validate(List<SourceRecord> records) {
        var result = new ArrayList<SourceRecord>();
        var invalidCount = 0;
        for (var r : records) {
            if (!r.isValid()) {
                invalidCount++;
                result.add(r);
                continue;
            }
            if (r.quantity() <= 0) {
                invalidCount++;
                result.add(SourceRecord.invalid(r.rawLine(), "数量必须为正：" + r.quantity()));
                continue;
            }
            if (r.unitPrice().signum() < 0) {
                invalidCount++;
                result.add(SourceRecord.invalid(r.rawLine(), "单价不能为负：" + r.unitPrice()));
                continue;
            }
            result.add(r);
        }
        if (invalidCount > 0) {
            note("validate", "标记了 " + invalidCount + " 条不合格记录");
        }
        return result;
    }

    /** 钩子：补全分类，大写并去掉空格。 */
    @Override
    protected List<SourceRecord> afterParse(List<SourceRecord> records) {
        return records.stream()
                .map(r -> r.isValid()
                        ? new SourceRecord(r.rawLine(), r.sku(), r.quantity(), r.unitPrice(),
                        r.category().trim().toUpperCase(java.util.Locale.ROOT), null)
                        : r)
                .toList();
    }

    /** 钩子：给价格超过 100 的行标记大额，但<b>不改数量</b>。 */
    @Override
    protected List<SourceRecord> transform(List<SourceRecord> records) {
        var highValue = records.stream()
                .filter(r -> r.isWritable() && r.lineTotal().compareTo(new BigDecimal("100")) > 0)
                .count();
        if (highValue > 0) {
            note("transform", "标记 " + highValue + " 条大额订单（>100），价格不变");
        }
        return records;
    }

    @Override
    protected void close() {
        System.out.println("    close: 导入结束");
    }

    @Override
    protected void write(List<SourceRecord> records) {
        var writable = records.stream().filter(SourceRecord::isWritable).toList();
        System.out.printf("    write: 写出 %d 条有效记录%n", writable.size());
        var bad = records.stream().filter(r -> !r.isValid()).toList();
        bad.forEach(r -> System.out.printf("      ✗ %s —— %s%n",
                r.rawLine().length() > 28 ? r.rawLine().substring(0, 28) + "…" : r.rawLine(),
                r.error()));
    }
}