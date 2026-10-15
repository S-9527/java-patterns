package demo.proxy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认实现 —— 内存存储。
 *
 * <p>故意在 {@link #find(String)} 里支持"前 N 次抛异常"，
 * 用来给重试代理做演示。真实服务不会有这种钩子，
 * 但测试需要一个可控的失败源。
 */
public final class DefaultUserService implements UserService {

    private final Map<String, User> store = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger();
    private final AtomicLong failFindNext = new AtomicLong();

    @Override
    public User add(String name, String email) {
        var id = "U-%04d".formatted(idGen.incrementAndGet());
        var user = new User(id, name, email);
        store.put(id, user);
        return user;
    }

    @Override
    public User find(String id) {
        // ⚠ 测试钩子：前 N 次调用抛异常，用来演示重试
        if (failFindNext.getAndDecrement() > 0) {
            throw new IllegalStateException("模拟瞬时故障");
        }
        var user = store.get(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在：" + id);
        }
        return user;
    }

    @Override
    public List<User> all() {
        return List.copyOf(store.values());
    }

    @Override
    public int count() {
        return store.size();
    }

    /** 让下一次 {@link #find} 抛异常。只给测试和 Demo 用。 */
    public void failFindNext() {
        failFindNext.incrementAndGet();
    }
}
