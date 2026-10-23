package io.openware.platform.tenant.domain.authorization.repository;

import io.openware.platform.tenant.domain.authorization.ConsumerAuthorization;
import io.openware.platform.tenant.domain.authorization.IamContext;
import io.openware.platform.tenant.domain.authorization.RoleBinding;
import io.openware.platform.tenant.domain.authorization.RoleScope;
import io.openware.platform.tenant.domain.iam.model.MaskingRole;
import java.util.List;
import java.util.Optional;

/**
 * IAM 授权快照装配仓储端口（读模型装配专用）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或持久化行对象（见 standards/10“端口命名与契约”）：
 * 上层拿到的是领域投影，行对象只在适配器里出现。
 *
 * <p><b>跨租户</b>：本端口承载的都是平台侧装配查询（账号当前能访问哪些租户/门店、某应用被授予了
 * 什么），实现必须跳过租户拦截器（{@code @InterceptorIgnore}）。租户边界由调用方在使用结果时收敛，
 * 而不是靠查询隐式过滤——隐式过滤会让"平台管理员可访问全部租户"退化成"只能看到当前租户"。
 */
public interface IamAuthorizationRepository {

  /** 按应用 + 账号读取消费者应用授权事实；无记录时为空。 */
  Optional<ConsumerAuthorization> findConsumerAuthorization(String appId, Long accountId);

  /** 读取账号在某作用域下的权限码（未归一：无权限时为空列表，**不返回 null**）。 */
  List<String> findPermissionCodes(Long accountId, Long tenantId, Long organizationId, Long storeId);

  /** 读取账号在某作用域下的授权版本（缓存失效依据）。 */
  Optional<Integer> findAuthorizationVersion(Long accountId, Long tenantId, Long organizationId, Long storeId);

  /**
   * 授予消费者应用授权。
   *
   * <p>返回是否生效：MySQL 对首次 INSERT 报 1 行、对 {@code ON DUPLICATE KEY UPDATE} 报 2 行，
   * 两者都表示授权已生效；**只有 0 表示应用不可用**。因此返回布尔而不是行数——
   * 行数语义反直觉，暴露出去必然被误用。
   */
  boolean grantConsumerAuthorization(String appId, Long accountId, String scope);

  /** 撤销消费者应用授权（解绑时调用）；保留审计行。 */
  boolean revokeConsumerAuthorization(String appId, Long accountId);

  /** 脱敏权限管理：预置角色及其可见 PII 状态（跨租户只读）。 */
  List<MaskingRole> findMaskingRoles();

  /** 账号角色绑定视图（角色名 + 作用域 + 门店名，供运营人员列表展示）。 */
  List<RoleBinding> findRoleBindings(Long accountId);

  /** 账号的可用角色绑定标识（撤销运营人员时用）。 */
  List<Long> findUserRoleIds(Long accountId);

  /** 按绑定标识读取账号（撤销时用于逐出该账号快照）。 */
  Optional<Long> findAccountIdByUserRole(Long userRoleId);

  /**
   * 撤销角色绑定：**软删**（{@code status → REVOKED}），返回是否影响了行。
   *
   * <p>与 {@link io.openware.platform.tenant.domain.iam.repository.UserRoleRepository#revoke} 是同一动作
   * 的两个入口：那条路径从 IAM 管理端点进入，这条从授权快照装配进入。两处都不做硬删——
   * 绑定是审计事实，硬删会让"谁曾经被授过什么"不可追溯。
   */
  boolean revokeUserRole(Long userRoleId);

  /** 统计账号持有的平台级角色数量（判定是否为平台管理员）。 */
  int countPlatformRoles(Long accountId);

  /** 账号全部可访问的经营上下文事实（逐行）。 */
  List<IamContext> findContexts(Long accountId);

  /** 账号全部角色码及其作用域（用于把角色归到上下文）。 */
  List<RoleScope> findRoleScopes(Long accountId);

  /** 全部活跃租户的上下文事实（平台管理员展开用）。 */
  List<IamContext> findAllTenantContexts();

  /** 某租户/组织下的启用门店上下文事实。 */
  List<IamContext> findActiveStores(Long tenantId, Long organizationId);
}
