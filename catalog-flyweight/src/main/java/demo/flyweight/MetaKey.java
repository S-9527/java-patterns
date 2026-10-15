package demo.flyweight;

/**
 * 池的键 —— 分类 + 品牌。
 *
 * <p>用 record 而不是拼字符串，是为了让"分类里有斜杠"这类边界情况
 * 不会导致键冲突。拼 {@code category + "/" + brand} 的话，
 * {@code ("a/b", "c")} 和 {@code ("a", "b/c")} 会撞。
 */
record MetaKey(String category, String brand) {
}