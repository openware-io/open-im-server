package io.openware.platform.tenant.domain.iam.model;

/**
 * 角色（领域模型）。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：由持久化适配器填充。
 *
 * @param id             角色标识
 * @param tenantId       租户；`PLATFORM` 级角色可为空
 * @param code           角色编码
 * @param name           角色名
 * @param roleType       角色类型：TENANT / PLATFORM 等
 * @param scopeLevel     作用域层级：PLATFORM / TENANT / STORE
 * @param domainCode     所属业务域
 * @param copyFromRoleId 复制来源角色（模板建角色时记录出处）
 * @param status         状态：ACTIVE / DISABLED
 */
public record Role(
    Long id,
    Long tenantId,
    String code,
    String name,
    String roleType,
    String scopeLevel,
    String domainCode,
    Long copyFromRoleId,
    String status) {

  /** 角色可用。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 平台级作用域。 */
  public static final String SCOPE_PLATFORM = "PLATFORM";

  /** 租户级作用域。 */
  public static final String SCOPE_TENANT = "TENANT";

  /** 门店级作用域。 */
  public static final String SCOPE_STORE = "STORE";

  /** 默认角色类型。 */
  public static final String TYPE_TENANT = "TENANT";

  /** 是否处于可用状态。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /** 作用域是否与给定层级一致。 */
  public boolean hasScopeLevel(String scopeLevel) {
    return this.scopeLevel != null && this.scopeLevel.equals(scopeLevel);
  }

  /** 新建角色：状态固定为 ACTIVE，尚未落库。 */
  public static Role create(Long tenantId, String code, String name, String roleType) {
    return new Role(null, tenantId, code, name,
        roleType == null || roleType.isBlank() ? TYPE_TENANT : roleType, null, null, null, STATUS_ACTIVE);
  }
}
