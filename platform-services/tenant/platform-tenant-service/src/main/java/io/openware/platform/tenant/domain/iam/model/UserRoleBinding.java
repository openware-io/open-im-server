package io.openware.platform.tenant.domain.iam.model;

import java.time.LocalDateTime;

/**
 * 账号-角色绑定（领域模型）。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：由持久化适配器填充。
 * {@code authorizationVersion} 保留：它是授权缓存的失效依据（每次重新授权自增），属并发/缓存语义。
 *
 * @param id                  绑定标识
 * @param accountId           账号
 * @param tenantId            租户
 * @param organizationId      组织
 * @param storeId             门店
 * @param roleId              角色
 * @param scopeType           作用域类型：PLATFORM / TENANT / STORE
 * @param effectiveFrom       生效起点
 * @param effectiveTo         生效终点
 * @param status              状态：ACTIVE / DISABLED
 * @param authorizationVersion 授权版本（缓存失效依据）
 */
public record UserRoleBinding(
    Long id,
    Long accountId,
    Long tenantId,
    Long organizationId,
    Long storeId,
    Long roleId,
    String scopeType,
    LocalDateTime effectiveFrom,
    LocalDateTime effectiveTo,
    String status,
    Integer authorizationVersion) {

  /** 绑定可用。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 平台级作用域。 */
  public static final String SCOPE_PLATFORM = "PLATFORM";

  /** 租户级作用域。 */
  public static final String SCOPE_TENANT = "TENANT";

  /** 门店级作用域。 */
  public static final String SCOPE_STORE = "STORE";

  /**
   * 新建绑定：状态固定为 ACTIVE、授权版本初值 1，尚未落库。
   *
   * <p>授权版本从 1 起（而非 0）：0 是"从未授权"的哨兵值，缓存按版本比对时会误判。
   */
  public static UserRoleBinding create(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                       Long roleId, String scopeType) {
    return new UserRoleBinding(null, accountId, tenantId, organizationId, storeId, roleId, scopeType,
        null, null, STATUS_ACTIVE, 1);
  }

  /** 是否处于可用状态。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /**
   * 作用域类型与其字段形状是否自洽。
   *
   * <p><b>这是此前散落在控制器条件表达式里的授权边界规则</b>，移进模型后可单独验证：
   * <ul>
   *   <li>{@code PLATFORM}：不得携带任何租户/组织/门店归属——平台级授权若挂了租户，
   *       会让该账号在某个租户下获得平台能力；</li>
   *   <li>{@code TENANT}：必须带租户，且不得带门店——带门店则实际是门店级授权；</li>
   *   <li>{@code STORE}：必须同时带租户与门店；</li>
   *   <li>其它取值：不合法。</li>
   * </ul>
   *
   * <p>未知作用域一律拒绝（fail-closed），避免新增作用域时忘记在此登记而静默放行。
   */
  public static boolean scopeShapeValid(String scopeType, Long tenantId, Long organizationId, Long storeId) {
    if (scopeType == null) {
      return false;
    }
    return switch (scopeType) {
      case SCOPE_PLATFORM -> tenantId == null && organizationId == null && storeId == null;
      case SCOPE_TENANT -> tenantId != null && storeId == null;
      case SCOPE_STORE -> tenantId != null && storeId != null;
      default -> false;
    };
  }
}
