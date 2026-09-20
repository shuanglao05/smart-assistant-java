package com.ipas.assistant.repository;

import com.ipas.assistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 账号仓储。对应里所有 {@code db.query(User).filter(...)} 的写法。
 *
 * <p>继承 {@code JpaRepository<User, Long>} 后自动获得 save / findById /
 * deleteById / count / findAll 等常用方法，不用手写实现。
 *
 * <p>Spring Data 的「方法名即查询」约定：只要方法名符合
 * {@code findBy + 字段名} 的命名规则，框架会在启动时自动生成对应的 JPQL/SQL。
 * 例如 {@code findByUsername} → {@code SELECT * FROM users WHERE username = ?}。
 * 这种方法在启动时就会校验字段是否存在，字段名写错会导致<b>启动失败</b>，
 * 而不是运行到一半才报错。
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

 /**
 * 按登录账号查用户。对应：
 * {@code db.query(User).filter(User.username == username).first()}
 *
 * @param username 登录账号
 * @return 用户（不存在时为空）
 */
 Optional<User> findByUsername(String username);

 /**
 * 判断账号是否已存在。对应注册接口里的重复检查：
 * {@code if db.query(User).filter(User.username == username).first(): raise 400}
 *
 * <p>用 {@code existsBy} 而不是先 {@code findByUsername} 再判空，
 * 是因为底层会生成 {@code SELECT ... LIMIT 1} 或 {@code COUNT}，
 * 不需要把整行数据（含可能几百 KB 的 avatar）读出来。
 *
 * @param username 登录账号
 * @return 是否已存在
 */
 boolean existsByUsername(String username);
}
