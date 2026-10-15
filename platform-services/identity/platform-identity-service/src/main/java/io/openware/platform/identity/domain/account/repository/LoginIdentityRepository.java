package io.openware.platform.identity.domain.account.repository;

import io.openware.platform.identity.domain.account.model.LoginIdentity;
import java.util.List;
import java.util.Optional;

/**
 * 登录标识仓储端口。
 *
 * <p>以聚合为单位，方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 *
 * <p>按 {@code (loginType, loginIdentifier)} 查询是登录流程的权威入口：该组合上的唯一性由数据库约束保证，
 * 因此 {@link #findByLoginIdentifier} 最多返回一条。
 */
public interface LoginIdentityRepository {

  /** 按登录类型 + 登录标识读取（登录流程的唯一权威入口）。 */
  Optional<LoginIdentity> findByLoginIdentifier(String loginType, String loginIdentifier);

  /**
   * 按登录类型 + 登录标识读取**处于可用状态**的登录标识（登录用例入口）。
   *
   * <p>状态过滤刻意收进端口：登录语义要求"仅可用标识可登录"，把它留给调用方拼接查询条件，正是
   * standards/10 禁止的"在应用层构造查询条件"。
   */
  Optional<LoginIdentity> findActiveByLoginIdentifier(String loginType, String loginIdentifier);

  /** 列出某账号下的全部登录标识。 */
  List<LoginIdentity> findByAccountId(Long accountId);

  /** 保存登录标识：{@code id} 为空时新增，否则更新。 */
  LoginIdentity save(LoginIdentity identity);
}
