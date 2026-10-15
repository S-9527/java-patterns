package demo.proxy;

/** 用户记录。 */
public record User(String id, String name, String email) {
    public User {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
    }
}
