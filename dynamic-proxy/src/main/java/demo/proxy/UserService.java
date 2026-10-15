package demo.proxy;

import java.util.List;

/**
 * 用户服务接口 —— <b>动态代理只能代理接口</b>，这是它和 CGLIB/ByteBuddy 的第一条区别。
 *
 * <p>编译期类型检查只保证接口方法存在，不保证实现类真的有这些方法。
 * 调用不存在的"方法"会得到 {@link UnsupportedOperationException}，
 * 而不是编译错误 —— 这是动态代理的代价之一。
 */
public interface UserService {

    User add(String name, String email);

    User find(String id);

    List<User> all();

    int count();
}
