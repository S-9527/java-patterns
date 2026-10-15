package demo.facade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算过程中流转的上下文。
 *
 * <p>它是<b>可变的</b>，这在本项目里是有意为之 —— 见类注释。
 *
 * <p>为什么结算上下文要可改：五个服务要往里写各自的结论（库存扣了多少、
 * 风控过没过、付了多少钱），最后汇总成一个 {@link Result}。
 * 如果每一步都返回新对象，就得在门面里手工搬运十几条数据。
 *
 * <p>这是<b>有界可变的典型形态</b>：只在结算线程内使用，不跨线程、不共享。
 * 和 {@code order-state} 场景里"状态是不可变 record 组件"的取舍正好相反。
 */
public final class CheckoutContext {

    private final CartDraft draft;
    private final List<Step> steps = new ArrayList<>();

    private BigDecimal quotedPrice = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private boolean stockReserved;
    private boolean riskPassed;
    private String orderId;

    public CheckoutContext(CartDraft draft) {
        this.draft = draft;
    }

    public CartDraft draft() {
        return draft;
    }

    public void record(String step, String detail) {
        steps.add(new Step(step, detail));
    }

    public List<Step> steps() {
        return List.copyOf(steps);
    }

    public BigDecimal quotedPrice() {
        return quotedPrice;
    }

    public void quotedPrice(BigDecimal amount) {
        this.quotedPrice = amount;
    }

    public BigDecimal paidAmount() {
        return paidAmount;
    }

    public void paidAmount(BigDecimal amount) {
        this.paidAmount = amount;
    }

    public boolean stockReserved() {
        return stockReserved;
    }

    public void stockReserved(boolean value) {
        this.stockReserved = value;
    }

    public boolean riskPassed() {
        return riskPassed;
    }

    public void riskPassed(boolean value) {
        this.riskPassed = value;
    }

    public String orderId() {
        return orderId;
    }

    public void orderId(String value) {
        this.orderId = value;
    }

    /** 中途失败要记下来，返回它。 */
    public Result fail(String reason) {
        return Result.rejected(reason, steps);
    }

    public Result succeed() {
        return Result.accepted(orderId, quotedPrice, paidAmount, steps);
    }

    public record Step(String name, String detail) {
    }

    /** 结算结果 —— sealed，两种结局。 */
    public sealed interface Result {

        record Accepted(String orderId, BigDecimal quoted, BigDecimal paid,
                        List<Step> trace) implements Result {
            public Accepted {
                trace = List.copyOf(trace);
            }
        }

        record Rejected(String reason, List<Step> trace) implements Result {
            public Rejected {
                trace = List.copyOf(trace);
            }

            static Rejected rejected(String reason, List<Step> trace) {
                return new Rejected(reason, trace);
            }
        }

        static Rejected rejected(String reason, List<Step> trace) {
            return new Rejected(reason, trace);
        }

        static Accepted accepted(String orderId, BigDecimal quoted, BigDecimal paid,
                                  List<Step> trace) {
            return new Accepted(orderId, quoted, paid, trace);
        }

        default boolean isAccepted() {
            return this instanceof Accepted;
        }
    }
}