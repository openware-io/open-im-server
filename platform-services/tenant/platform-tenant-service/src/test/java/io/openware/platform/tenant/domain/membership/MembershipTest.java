package io.openware.platform.tenant.domain.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MembershipTest {

  @Test
  void createStaffMembership() {
    Membership membership = Membership.create(10L, 100L, 5L, "STAFF", "E001");
    assertEquals(10L, membership.accountId());
    assertEquals(100L, membership.tenantId());
    assertEquals(5L, membership.organizationId());
    assertEquals("STAFF", membership.membershipType());
    assertEquals("E001", membership.memberNo());
    assertEquals(1, membership.authorizationVersion());
    assertEquals("ACTIVE", membership.status());
  }

  @Test
  void leaveIncrementsAuthorizationVersion() {
    Membership membership = Membership.create(10L, 100L, 0L, "STAFF", "E001");
    Membership left = membership.changeStatus("LEFT");
    assertEquals("LEFT", left.status());
    assertEquals(2, left.authorizationVersion());
  }

  @Test
  void leftCannotBeReactivated() {
    Membership left = Membership.create(10L, 100L, 0L, "STAFF", "E001").changeStatus("LEFT");
    assertThrows(IllegalStateException.class, () -> left.changeStatus("ACTIVE"));
  }

  @Test
  void customerAndStaffNeedValidType() {
    assertThrows(IllegalArgumentException.class,
        () -> Membership.create(10L, 100L, 0L, "OWNER", "X"));
  }

  @Test
  void tenantAndAccountAreRequired() {
    assertThrows(IllegalArgumentException.class,
        () -> Membership.create(null, 100L, 0L, "STAFF", "E001"));
    assertThrows(IllegalArgumentException.class,
        () -> Membership.create(10L, null, 0L, "STAFF", "E001"));
  }
}
