package demo.pipeline;

/**
 * 流水线里流动的数据 —— 一条待导入的记录。
 *
 * <p>刻意做成<b>不可变 record</b>：流水线的每一步都返回新记录，
 * 这样中间状态永远可回溯、也不存在"半处理完"的脏对象。
 */
public record SourceRecord(
        String rawLine,
        String sku,
        int quantity,
        java.math.BigDecimal unitPrice,
        String category,
        String error) {

    public SourceRecord {
        if (rawLine == null) {
            throw new IllegalArgumentException("原始行不能为 null");
        }
    }

    /** 带错误标记的记录。校验步骤用它把不合格的行标出来而不是直接丢掉。 */
    public static SourceRecord invalid(String rawLine, String reason) {
        return new SourceRecord(rawLine, null, 0, null, null, reason);
    }

    public boolean isValid() {
        return error == null;
    }

    /** 解析失败或校验不通过的记录不参与写出。 */
    public boolean isWritable() {
        return isValid() && sku != null && quantity > 0 && unitPrice != null;
    }

    public java.math.BigDecimal lineTotal() {
        return unitPrice.multiply(java.math.BigDecimal.valueOf(quantity));
    }
}