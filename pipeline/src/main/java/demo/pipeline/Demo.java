package demo.pipeline;

import java.util.List;

/**
 * 模板方法：导入流水线的骨架。
 *
 * <p>同时演示策略版写法，末尾有对照。
 */
public final class Demo {

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        var csv = List.of(
                "SKU-1001,2,39.90,ELECTRONICS",
                "SKU-2044,1,128.00,BOOKS",
                "# 这一行是注释，应该被跳过",
                "SKU-3310,3,15.50,ELECTRONICS",
                "SKU-4004,0,25.00,BOOKS",        // 数量为 0 → 校验失败
                "SKU-5005,1,-3.00,BOOKS",         // 单价为负 → 校验失败
                "SKU-6006,abc,10.00,BOOKS",       // 数量不是数字 → 解析失败
                "SKU-7007,1",                     // 列数不对 → 解析失败
                "");

        heading("CSV 导入（覆写全部必选步骤 + 两个钩子）");
        var csvReports = new CsvImportPipeline(csv).run();
        csvReports.forEach(r -> System.out.println("  " + r.describe()));
        notes(csvReports);

        heading("TSV 导入（只实现 3 个抽象方法）");
        var tsv = List.of(
                "SKU-1001\t2\t39.90\tELECTRONICS",
                "SKU-2044\t1\t128.00",
                "SKU-3310\t3\t15.50\tELECTRONICS");
        var tsvReports = new TsvImportPipeline(tsv).run();
        tsvReports.forEach(r -> System.out.println("  " + r.describe()));
        System.out.println();
        System.out.println("  它没覆写 open/close/afterParse/transform —— 全部走默认实现。");
        System.out.println("  8 步流水线一步不少，这是模板方法相对策略的实际优势。");

        heading("策略版写法（同一件事）");
        var strategyReports = StrategyPipeline.csvBased(csv).run();
        strategyReports.stream()
                .filter(r -> !r.step().contains("."))     // 只看主步骤，略过 detail 行
                .forEach(r -> System.out.println("  " + r.describe()));
        System.out.println();
        System.out.println("  注意上面多了两行 parse.raw / validate.detail —— ");
        System.out.println("  策略版的 Step 接口要额外接一个 List<StepReport> 才能往报告里写东西，");
        System.out.println("  模板方法的子类直接调父类的 note()，不需要传状态。");

        heading("两版产出等价");
        var tplWritten = new CsvImportPipeline(csv).run().getLast().outCount();
        var stgWritten = StrategyPipeline.csvBased(csv).run().getLast().outCount();
        System.out.println("  模板法写出 %d 条 / 策略法写出 %d 条 → 结果等价，可互换"
                .formatted(tplWritten, stgWritten));
        System.out.println("  差别不在结果，在骨架能不能改：");
        System.out.println();
        System.out.println("""
                              模板方法（继承）        策略（组合）
                骨架            final 方法，顺序锁死     shell 里写死，但可整体替换
                加一步          改父类，所有子类自动有   改 shell，所有策略自动有
                换掉某一步      要新建子类（编译期）      传个新 lambda（运行期）
                换掉顺序        要新建子类              要新建 shell
                运行期动态选    不行                    行
                类型检查        编译期                  编译期
                代价            继承耦合               参数列表变长""");
        System.out.println();
        System.out.println("  选哪个：");
        System.out.println("  - 流程永远不变、只是步骤实现不同 → 模板方法（继承树浅）");
        System.out.println("  - 需要运行期换步骤，或流程本身也是变量 → 策略");
        System.out.println("  - 两者都要 → 模板方法定流程，策略填步骤（Spring 就是这么干的）");
    }

    private static void notes(List<StepReport> reports) {
        var withNotes = reports.stream().filter(r -> !r.notes().isEmpty()).toList();
        if (withNotes.isEmpty()) {
            return;
        }
        System.out.println();
        withNotes.forEach(r -> r.notes().forEach(n -> System.out.println("  · " + n)));
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("── %s ".formatted(title) + "─".repeat(Math.max(0, 58 - title.length())));
        System.out.println();
    }
}