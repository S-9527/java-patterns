package demo.pipeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 同一件事的<b>策略版</b>写法，用来和 {@link ImportPipeline} 对照。
 *
 * <p>骨架在这里是 {@link #templateMethod} 这个 final 方法；步骤本身是接口。
 *
 * <p>对照点：
 * <ul>
 *   <li>骨架被锁死了 —— 步骤顺序只有一种可能</li>
 *   <li>要加第 9 步，得改 {@code ImportPipeline}，所有子类自动获得</li>
 *   <li>要改顺序，得写一个新的流水线类</li>
 * </ul>
 *
 * <p>注意它复用了 CSV 的 parse/validate 逻辑，所以两个版本跑出来的结果可以直接对比 ——
 * {@code StrategyVsTemplateTest} 会断言这一点。
 */
public final class StrategyPipeline {

    /**
     * 一个步骤：原始文本 → 记录。
     *
     * <p>注意它有<b>两个</b>参数：输入，和一个共享的报告列表。
     *
     * <p><b>这是策略版比模板方法差的地方</b>：策略是纯函数式的，一个输入一个输出，
     * 想让它往报告里写点东西，就必须把可变状态从外面递进去。
     * 模板方法的 {@code note()} 是父类方法，子类直接调用就行，不需要传状态。
     *
     * <p>如果步骤不需要记说明，用单参的 {@code RecordStep} 更干净 ——
     * 这里为了和模板方法对照产出同样的报告，刻意保留了这个设计缺陷。
     */
    @FunctionalInterface
    public interface Step {
        List<SourceRecord> apply(List<String> input, List<StepReport> reports);
    }

    /** 已经解析成记录之后的步骤。 */
    @FunctionalInterface
    public interface RecordStep {
        List<SourceRecord> apply(List<SourceRecord> input, List<StepReport> reports);
    }

    @FunctionalInterface
    public interface Reader {
        List<String> read();
    }

    @FunctionalInterface
    public interface Writer {
        void write(List<SourceRecord> records);
    }

    private final Reader reader;
    private final Writer writer;
    private final Step parse;
    private final RecordStep validate;
    private final RecordStep normalize;

    public StrategyPipeline(Reader reader, Step parse, RecordStep validate,
                            RecordStep normalize, Writer writer) {
        this.reader = reader;
        this.parse = parse;
        this.validate = validate;
        this.normalize = normalize;
        this.writer = writer;
    }

    public List<StepReport> run() {
        var reports = new ArrayList<StepReport>();

        var raw = reader.read();
        reports.add(new StepReport("read", raw.size(), raw.size(), List.of()));

        var parsed = parse.apply(raw, reports);
        reports.add(new StepReport("parse", raw.size(), parsed.size(), List.of()));

        var validated = validate.apply(parsed, reports);
        reports.add(new StepReport("validate", parsed.size(), validated.size(), List.of()));

        var normalized = normalize.apply(validated, reports);
        reports.add(new StepReport("normalize", validated.size(), normalized.size(), List.of()));

        var writable = normalized.stream().filter(SourceRecord::isWritable).toList();
        writer.write(writable);
        reports.add(new StepReport("write", normalized.size(), writable.size(), List.of()));

        return List.copyOf(reports);
    }

    /**
     * 壳 + 策略：shell 负责流程，策略负责每一步做什么。
     *
     * <p>这是模板方法的现代替代品。它比继承的好处是<b>能在运行时换掉某一步</b>
     * （比如"这次导入用严格校验，下次用宽松校验"），模板方法做不到 —— 那是编译期定死的。
     */
    public static StrategyPipeline csvBased(List<String> source) {
        return new StrategyPipeline(
                () -> source,
                StrategyPipeline::parseAll,
                CsvRules::validateAll,
                CsvRules::normalize,
                records -> System.out.printf("    write: 写出 %d 条有效记录%n", records.size()));
    }

    /** 公开的解析入口，方便测试和外部复用同一套解析逻辑。 */
    public static List<SourceRecord> parseAllForTest(List<String> raw, List<StepReport> reports) {
        return parseAll(raw, reports);
    }

    private static List<SourceRecord> parseAll(List<String> raw, List<StepReport> reports) {
        var records = raw.stream().map(CsvRules::parseLine).flatMap(List::stream).toList();
        var invalid = (int) records.stream().filter(r -> !r.isValid()).count();
        reports.add(new StepReport("parse.raw", raw.size(), records.size(),
                List.of("解析失败 %d 条".formatted(invalid))));
        return records;
    }

    /** 抽出来的共用规则，让两个版本能产出可比较的结果。 */
    static final class CsvRules {

        private CsvRules() {
        }

        /** 把一行文本变成 0 或 1 条记录（坏行变成带 error 的记录）。 */
        static List<SourceRecord> parseLine(String line) {
            if (line.isBlank() || line.startsWith("#")) {
                return List.of();
            }
            var cols = line.split(",");
            if (cols.length != 4) {
                return List.of(SourceRecord.invalid(line, "列数不对，期望 4 实际 " + cols.length));
            }
            try {
                return List.of(new SourceRecord(line, cols[0].trim(),
                        Integer.parseInt(cols[1].trim()),
                        new BigDecimal(cols[2].trim()), cols[3].trim(), null));
            } catch (NumberFormatException e) {
                return List.of(SourceRecord.invalid(line, "数字解析失败：" + e.getMessage()));
            }
        }

        /** 不记报告的纯校验版，方便测试直接调用。 */
    public static List<SourceRecord> validateAllQuietly(List<SourceRecord> records) {
        return validateAll(records, new ArrayList<>());
    }

    static List<SourceRecord> validateAll(List<SourceRecord> records, List<StepReport> reports) {
            var result = new ArrayList<SourceRecord>();
            var newlyInvalid = 0;
            for (var r : records) {
                if (!r.isValid()) {
                    result.add(r);          // 已经坏了的，原样带着走
                } else if (r.quantity() <= 0) {
                    newlyInvalid++;
                    result.add(SourceRecord.invalid(r.rawLine(), "数量必须为正：" + r.quantity()));
                } else if (r.unitPrice().signum() < 0) {
                    newlyInvalid++;
                    result.add(SourceRecord.invalid(r.rawLine(), "单价不能为负：" + r.unitPrice()));
                } else {
                    result.add(r);
                }
            }
            if (newlyInvalid > 0) {
                reports.add(new StepReport("validate.detail", records.size(), result.size(),
                        List.of("标记了 %d 条不合格记录".formatted(newlyInvalid))));
            }
            return List.copyOf(result);
        }

        /** 不记报告的纯归一化版。 */
    public static List<SourceRecord> normalizeQuietly(List<SourceRecord> records) {
        return normalize(records, new ArrayList<>());
    }

    static List<SourceRecord> normalize(List<SourceRecord> records, List<StepReport> reports) {
            return records.stream()
                    .map(r -> r.isValid()
                            ? new SourceRecord(r.rawLine(), r.sku(), r.quantity(), r.unitPrice(),
                            r.category().trim().toUpperCase(java.util.Locale.ROOT), null)
                            : r)
                    .toList();
        }
    }
}