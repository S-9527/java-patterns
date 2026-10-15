package demo.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 等价性测试：模板方法版和策略版跑同一份数据，应该产出<b>相同的结果</b>。
 *
 * <p>意义在于证明两者的差别不在行为，而在<b>骨架的可变性</b> ——
 * 那是纯设计决策，不该被"哪个更正确"绑架。
 */
@DisplayName("模板方法 vs 策略 —— 结果等价，差别在骨架")
class StrategyVsTemplateTest {

    private static final List<String> DATA = List.of(
            "SKU-1001,2,39.90,ELECTRONICS",
            "SKU-2044,1,128.00,books",
            "SKU-3310,3,15.50,ELECTRONICS",
            "SKU-4004,0,25.00,BOOKS",
            "SKU-5005,1,-3.00,BOOKS",
            "SKU-6006,abc,10.00,BOOKS");

    @Test
    @DisplayName("两版最终写出的有效记录完全一致")
    void sameOutcome() {
        // 模板法：子类覆写 + 钩子
        var templateReports = new CsvImportPipeline(DATA).run();
        var templateValid = templateReports.getLast().outCount();

        // 策略法：shell + lambda
        var strategyReports = StrategyPipeline.csvBased(DATA).run();
        var strategyValid = strategyReports.getLast().outCount();

        assertEquals(3, templateValid, "6 行里 3 行有效（数量0/负价/非数字各 1 条）");
        assertEquals(templateValid, strategyValid, "两版有效记录数相同");
    }

    @Test
    @DisplayName("两版对坏行的判定一致")
    void sameInvalidDetection() {
        var templateBad = new CsvImportPipeline(DATA).run().stream()
                .filter(r -> r.step().equals("parse"))
                .findFirst().orElseThrow()
                .outCount();

        var strategyBad = StrategyPipeline.csvBased(DATA).run().stream()
                .filter(r -> r.step().equals("parse"))
                .findFirst().orElseThrow()
                .outCount();

        assertEquals(templateBad, strategyBad, "解析后的记录条数一致（6 条，含坏行标记）");
    }

    @Test
    @DisplayName("归一化效果一致 —— 分类统一大写")
    void sameNormalization() {
        var normalized = StrategyPipeline.CsvRules.normalizeQuietly(List.of(
                new SourceRecord("x", "A", 1, BigDecimal.ONE, "books", null),
                new SourceRecord("y", "B", 1, BigDecimal.ONE, " electronics ", null)));

        assertEquals(List.of("BOOKS", "ELECTRONICS"),
                normalized.stream().map(SourceRecord::category).toList());
    }

    @Test
    @DisplayName("策略版能在运行期换掉某一步 —— 模板方法做不到")
    void strategySwappableAtRuntime() {
        var results = new ArrayList<Integer>();

        // 同一套 shell，换掉 validate 步骤
        for (var strict : List.of(true, false)) {
            var pipeline = new StrategyPipeline(
                    () -> DATA,
                    StrategyPipeline::parseAllForTest,
                    strict ? StrategyPipeline.CsvRules::validateAll
                           : (records, reports) -> records,     // 宽松：什么都不拦
                    StrategyPipeline.CsvRules::normalize,
                    records -> {
                    });
            results.add(pipeline.run().getLast().outCount());
        }

        assertEquals(3, results.getFirst(), "严格校验：3 条有效");
        assertEquals(4, results.get(1), "宽松校验：多放行一条（数量 0 那条）");
    }
}