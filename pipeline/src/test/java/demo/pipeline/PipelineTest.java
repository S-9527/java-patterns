package demo.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("模板方法 —— 导入流水线")
class PipelineTest {

    private static final List<String> SAMPLE = List.of(
            "SKU-1001,2,39.90,ELECTRONICS",
            "SKU-2044,1,128.00,BOOKS",
            "# 注释",
            "SKU-3310,3,15.50,ELECTRONICS",
            "SKU-4004,0,25.00,BOOKS",
            "SKU-5005,1,-3.00,BOOKS",
            "SKU-6006,abc,10.00,BOOKS",
            "SKU-7007,1",
            "");

    @Test
    @DisplayName("⚠ 步骤顺序被 final 锁死 —— 这是模板方法的核心契约")
    void stepOrderIsFixed() throws NoSuchMethodException {
        var reports = new CsvImportPipeline(SAMPLE).run();

        assertEquals(List.of("readRaw", "parse", "afterParse", "validate", "transform", "write"),
                reports.stream().map(StepReport::step).toList(),
                "不论子类怎么覆写，顺序都是这 6 步");

        // run() 是 final —— 用反射确认，这是模板方法的契约所在
        var run = ImportPipeline.class.getDeclaredMethod("run");
        assertTrue(java.lang.reflect.Modifier.isFinal(run.getModifiers()),
                "run() 必须 final：顺序是锁死的契约");
        assertFalse(java.lang.reflect.Modifier.isAbstract(run.getModifiers()),
                "run() 必须有实现，不能是抽象的");
    }

    @Test
    @DisplayName("注释行和空行被跳过，不算错误")
    void skipsCommentsAndBlanks() {
        var reports = new CsvImportPipeline(SAMPLE).run();
        var read = reports.getFirst();
        var parse = reports.get(1);

        // 原始 9 行 → 解析后 7 条（跳过 1 注释 + 1 空行；坏行仍进结果，带 error 标记）
        assertEquals(9, read.inCount(), "readRaw 读到全部 9 行");
        assertEquals(7, parse.outCount(), "注释和空行不进结果");
    }

    @Test
    @DisplayName("⚠ 坏行不消失，而是带 error 标记 —— 报告能说清坏了几条")
    void badLinesAreMarkedNotDropped() {
        var reports = new CsvImportPipeline(SAMPLE).run();
        var validate = reports.get(3);

        // 校验不删行，只打标记：进出条数必须相等
        assertEquals(validate.inCount(), validate.outCount(), "校验不删行，只打标记");

        // SAMPLE 里共 4 条不合格：2 条解析失败 + 数量 0 + 单价为负
        assertEquals(7, validate.outCount(), "7 条全部保留，没有一条被丢弃");
        var validateNotes = validate.notes();
        assertTrue(validateNotes.stream().anyMatch(n -> n.contains("标记了 4 条")),
                "校验步骤记录了标记数量：" + validateNotes);

        // 最终只有 3 条真正写出去
        var write = reports.get(5);
        assertEquals(7, write.inCount(), "传给 write 的是 7 条");
        assertEquals(3, write.outCount(), "write 过滤掉带 error 的，实际写出 3 条");
    }

    @Test
    @DisplayName("TSV 只实现 3 个抽象方法就跑完 8 步 —— 钩子默认值可用")
    void defaultHooksAreUsable() {
        var reports = new TsvImportPipeline(List.of(
                "SKU-1001\t2\t39.90\tELECTRONICS",
                "SKU-2044\t1\t128.00")).run();

        assertEquals(List.of("readRaw", "parse", "afterParse", "validate", "transform", "write"),
                reports.stream().map(StepReport::step).toList());
        assertEquals(2, reports.getLast().outCount(), "两行都有效");
    }

    @Test
    @DisplayName("钩子可以有 extend 语义，也可以完全替换")
    void hookExtendVsReplace() {
        // CSV 覆写 afterParse 做归一化，分类变大写
        var reports = new CsvImportPipeline(List.of(
                "sku-1001,1,10.00,electronics")).run();
        assertEquals(1, reports.getLast().outCount());

        // 完全替换：匿名子类把有效记录全变成无效记录
        var nuking = new ImportPipeline() {
            @Override
            protected List<String> readRaw() {
                return List.of("raw-1");
            }

            @Override
            protected List<SourceRecord> parse(List<String> raw) {
                return List.of(new SourceRecord(raw.getFirst(), "A", 1,
                        BigDecimal.TEN, "X", null));
            }

            @Override
            protected List<SourceRecord> validate(List<SourceRecord> records) {
                return records.stream()
                        .map(r -> SourceRecord.invalid(r.rawLine(), "被替换的校验"))
                        .toList();
            }
        };
        var nuked = nuking.run();
        assertEquals(1, nuked.get(3).outCount(), "validate 仍产出 1 条（带 error）");
        assertEquals(0, nuked.getLast().outCount(), "write 阶段一条都写不出去");
    }

    @Test
    @DisplayName("run() 可重复调用，不会累积上一次的状态")
    void runIsRepeatable() {
        var pipeline = new CsvImportPipeline(SAMPLE);
        var first = pipeline.run();
        var second = pipeline.run();

        assertEquals(
                first.stream().map(StepReport::describe).toList(),
                second.stream().map(StepReport::describe).toList(),
                "报告在 run() 开头被 clear，两次结果一致");
    }

    @Test
    @DisplayName("报告不可变")
    void reportIsImmutable() {
        var reports = new CsvImportPipeline(SAMPLE).run();
        assertThrows(UnsupportedOperationException.class,
                () -> reports.getFirst().notes().add("偷偷加一条"));
    }

    @Test
    @DisplayName("子类没实现抽象方法就编译不过 —— 这是抽象基类的价值")
    void abstractMethodsAreEnforced() {
        // 用一个匿名子类验证：必须实现全部 3 个抽象方法
        var ok = new ImportPipeline() {
            @Override
            protected List<String> readRaw() {
                return List.of();
            }

            @Override
            protected List<SourceRecord> parse(List<String> raw) {
                return List.of();
            }

            @Override
            protected List<SourceRecord> validate(List<SourceRecord> records) {
                return records;
            }
        };
        assertEquals(0, ok.run().getFirst().inCount(), "空输入也能跑完全部步骤");
    }

    @Test
    @DisplayName("SourceRecord 的可写判定")
    void writablePredicate() {
        assertTrue(new SourceRecord("x", "A", 1, BigDecimal.ONE, "C", null).isWritable());
        assertFalse(new SourceRecord("x", "A", 0, BigDecimal.ONE, "C", null).isWritable(),
                "数量为 0 不可写");
        assertFalse(SourceRecord.invalid("x", "坏了").isWritable());
        assertFalse(SourceRecord.invalid("x", "坏了").isValid());
    }

    @Test
    @DisplayName("open/close 钩子按序被调用")
    void openAndCloseHooksCalled() {
        var log = new ArrayList<String>();
        var pipeline = new ImportPipeline() {
            @Override
            protected void open() {
                log.add("open");
            }

            @Override
            protected List<String> readRaw() {
                return List.of("A,1,1.00,X");
            }

            @Override
            protected List<SourceRecord> parse(List<String> raw) {
                return StrategyPipeline.CsvRules.parseLine(raw.getFirst());
            }

            @Override
            protected List<SourceRecord> validate(List<SourceRecord> records) {
                return StrategyPipeline.CsvRules.validateAllQuietly(records);
            }

            @Override
            protected void close() {
                log.add("close");
            }
        };
        pipeline.run();
        assertEquals(List.of("open", "close"), log);
    }
}