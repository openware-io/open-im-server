package io.openware.platform.tenant.domain.iam.repository;

import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
import java.util.List;
import java.util.Optional;

/**
 * 账号-角色绑定仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface UserRoleRepository {

  /** 新增绑定，返回落库后的聚合（含回填主键）。 */
  UserRoleBinding insert(UserRoleBinding binding);

  /**
   * 按标识读取绑定**归属的账号**。
   *
   * <p>独立于 {@link #revoke}：撤销的调用方需要先知道绑定存在且拿到账号，才能①对不存在的绑定返回 404
   * 而不是静默成功，②只逐出该账号的权限快照而不是全量逐出。
   */
  Optional<Long> findAccountIdById(Long userRoleId);

  /**
   * 撤销绑定：**软删**（{@code status → REVOKED}），保留行以支撑审计与历史回溯。
   *
   * <p>返回是否确实影响了行；调用方据此判断并发下是否已被他人撤销。
   *
   * <p><b>跨租户</b>：实现必须跳过租户拦截器（{@code @InterceptorIgnore}）——撤销由运营/平台侧发起，
   * 绑定可能属于任意租户，租户边界由调用方的权限校验保证，而不是靠这条 UPDATE 隐式过滤
   * （隐式过滤会让"撤销别人的绑定"变成静默成功却零行的假象）。
   */
  boolean revoke(Long userRoleId);

  /** 列出某账号的绑定标识（按标识升序）。 */
  List<Long> findIdsByAccount(Long accountId);

  /** 按账号 + 角色撤销绑定（角色人员移除；跨租户由调用方校验）。 */
  boolean revokeByAccountAndRole(Long accountId, Long roleId);
}
