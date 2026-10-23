package io.openware.platform.tenant.domain.iam.model;

/**
 * 成员-角色绑定：租户/组织/门店角色必须绑定 membership，不直接绑定全局 account。
 */
public record RoleBinding(
    Long id,
    Long membershipId,
    Long roleId,
    String scopeType,
    Long tenantId,
    Long organizationId,
    Long storeId,
    int authorizationVersion,
    String status) {

  public static final String SCOPE_TENANT = "TENANT";
  public static final String SCOPE_ORGANIZATION = "ORGANIZATION";
  public static final String SCOPE_STORE = "STORE";
  public static final String SCOPE_SELF = "SELF";
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REVOKED = "REVOKED";

  public static RoleBinding create(Long membershipId, Long roleId, String scopeType,
                                   Long tenantId, Long organizationId, Long storeId) {
    if (membershipId == null || membershipId <= 0) {
      throw new IllegalArgumentException("membershipId required");
    }
    if (roleId == null || roleId <= 0) {
      throw new IllegalArgumentException("roleId required");
    }
    if (tenantId == null || tenantId <= 0) {
      throw new IllegalArgumentException("tenantId required");
    }
    if (!scopeShapeValid(scopeType, tenantId, organizationId, storeId)) {
      throw new IllegalArgumentException("role binding scope shape invalid");
    }
    return new RoleBinding(null, membershipId, roleId, scopeType, tenantId,
        organizationId == null ? 0L : organizationId, storeId == null ? 0L : storeId,
        1, STATUS_ACTIVE);
  }

  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  public RoleBinding revoke() {
    return new RoleBinding(id, membershipId, roleId, scopeType, tenantId, organizationId, storeId,
        authorizationVersion + 1, STATUS_REVOKED);
  }

  public RoleBinding changeScope(String newScopeType, Long newOrganizationId, Long newStoreId) {
    if (!scopeShapeValid(newScopeType, tenantId, newOrganizationId, newStoreId)) {
      throw new IllegalArgumentException("role binding scope shape invalid");
    }
    return new RoleBinding(id, membershipId, roleId, newScopeType, tenantId,
        newOrganizationId == null ? 0L : newOrganizationId,
        newStoreId == null ? 0L : newStoreId, authorizationVersion + 1, status);
  }

  public static boolean scopeShapeValid(String scopeType, Long tenantId, Long organizationId, Long storeId) {
    if (scopeType == null) {
      return false;
    }
    return switch (scopeType) {
      case SCOPE_TENANT -> tenantId != null && storeId == null;
      case SCOPE_ORGANIZATION -> tenantId != null && organizationId != null && storeId == null;
      case SCOPE_STORE -> tenantId != null && storeId != null;
      case SCOPE_SELF -> tenantId != null && organizationId == null && storeId == null;
      default -> false;
    };
  }
}
