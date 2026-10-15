package io.openware.platform.admin.domain.repository;

import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SaaS 后台管理员账号仓储端口。
 *
 * <p>以聚合为单位，方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型
 * （见 standards/10“端口命名与契约”）。
 *
 * <p>审计字段由适配器在 {@link #save} 内填充，调用方不感知；{@code created_at} 是 NOT NULL 无默认值列，
 * 漏填会让 INSERT 失败。
 */
public interface ConsAdminAccountRepository {

    Optional<ConsAdminAccount> findByUsername(String username);

    Optional<ConsAdminAccount> findBySubject(String idaasSubject);

    Optional<ConsAdminAccount> findById(Long accountId);

    /**
     * 按角色列出账号（按标识升序），可选**创建时间闭区间**过滤。
     *
     * <p>时间区间是查询语义的一部分，收在端口里，避免调用方在应用层拼接查询条件。
     * 任一参数为 {@code null} 时该端不设边界。
     */
    List<ConsAdminAccount> listByRole(AdminRole role, LocalDateTime fromInclusive, LocalDateTime toInclusive);

    /** 登录名是否已存在（开通用例的唯一性判据）。 */
    boolean existsByUsername(String username);

    /** 该平台账号是否已绑定后台账号（开通／换绑用例的判据）。 */
    boolean existsByPlatformAccountId(Long platformAccountId);

    /**
     * 除指定账号外，该平台账号是否已绑定后台账号（换绑时的判据）。
     *
     * <p>与 {@link #existsByPlatformAccountId} 分开：换绑时"绑在自己身上"不算冲突。
     */
    boolean existsByPlatformAccountIdExcluding(Long platformAccountId, Long excludedAccountId);

    /**
     * 保存账号：{@code id} 为空时新增，否则更新，返回落库后的聚合（含生成的标识）。
     *
     * <p>审计字段由适配器填充；乐观锁冲突等持久化异常由适配器抛出，不得静默覆盖。
     */
    ConsAdminAccount save(ConsAdminAccount account);

    /** 按标识删除账号，返回是否确实删除了一行。 */
    boolean deleteById(Long accountId);

    /** 更新密码哈希，返回受影响行数。 */
    int updatePassword(Long accountId, String passwordHash);
}
