package io.openware.platform.tenant.domain.membership;

/**
 * 租户成员身份：账号在某个租户内的员工/客户身份。
 *
 * <p>账号是全局的，成员身份是租户内的；角色绑定必须指向 membership。
 */
public record Membership(
    Long id,
    Long accountId,
    Long tenantId,
    Long organizationId,
    String membershipType,
    String memberNo,
    int authorizationVersion,
    String status) {

  public static final String TYPE_STAFF = "STAFF";
  public static final String TYPE_CUSTOMER = "CUSTOMER";
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_SUSPENDED = "SUSPENDED";
  public static final String STATUS_LEFT = "LEFT";

  public static Membership create(Long accountId, Long tenantId, Long organizationId,
                                  String membershipType, String memberNo) {
    if (accountId == null || accountId <= 0) {
      throw new IllegalArgumentException("accountId required");
    }
    if (tenantId == null || tenantId <= 0) {
      throw new IllegalArgumentException("tenantId required");
    }
    if (!TYPE_STAFF.equals(membershipType) && !TYPE_CUSTOMER.equals(membershipType)) {
      throw new IllegalArgumentException("membershipType must be STAFF or CUSTOMER");
    }
    if (memberNo == null || memberNo.isBlank()) {
      throw new IllegalArgumentException("memberNo required");
    }
    return new Membership(null, accountId, tenantId, organizationId == null ? 0L : organizationId,
        membershipType, memberNo.trim(), 1, STATUS_ACTIVE);
  }

  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }

  public Membership changeStatus(String target) {
    if (!STATUS_ACTIVE.equals(target) && !STATUS_SUSPENDED.equals(target) && !STATUS_LEFT.equals(target)) {
      throw new IllegalArgumentException("unsupported membership status");
    }
    if (STATUS_LEFT.equals(status) && !STATUS_LEFT.equals(target)) {
      throw new IllegalStateException("LEFT membership cannot be reactivated");
    }
    return new Membership(id, accountId, tenantId, organizationId, membershipType, memberNo,
        authorizationVersion + 1, target);
  }
}
