package io.openware.platform.tenant.domain.iam.model;

import java.util.List;

/**
 * 权限点（领域模型）。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：由持久化适配器填充。
 *
 * @param id              权限标识
 * @param code            权限编码（形如 {@code resource:action}）
 * @param module          所属模块
 * @param resource        资源
 * @param action          动作
 * @param description     说明
 * @param scopeLevel      权限自身的作用域层级
 * @param domainCode      所属业务域
 * @param grantableLevels 可授予的作用域层级，逗号分隔（如 {@code "TENANT,STORE"}）
 * @param menuCode        关联菜单
 * @param status          状态：ACTIVE / DISABLED
 */
public record Permission(
    Long id,
    String code,
    String module,
    String resource,
    String action,
    String description,
    String scopeLevel,
    String domainCode,
    String grantableLevels,
    String menuCode,
    String status) {

  /** 权限可用。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 是否处于可用状态。 */
  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  /**
   * 该权限是否允许授予给指定作用域层级。
   *
   * <p><b>这是授权正确性的核心规则</b>，此前散落在控制器里：角色只能拿到其作用域在
   * {@code grantableLevels} 白名单内的权限。否则一个门店级角色就能被授予平台级权限。
   *
   * <p>严格判空：作用域或白名单缺失一律视为不可授予（fail-closed）。白名单是逗号分隔的层级清单，
   * 逐项去空白后精确匹配——不做前缀或包含匹配，避免 {@code TENANT} 误配 {@code TENANT_ADMIN}。
   */
  public boolean grantableTo(String roleScopeLevel) {
    if (roleScopeLevel == null || grantableLevels == null || grantableLevels.isBlank()) {
      return false;
    }
    return List.of(grantableLevels.split(",")).stream()
        .map(String::trim)
        .anyMatch(roleScopeLevel::equals);
  }
}
