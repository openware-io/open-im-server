package com.gvchat.platform.admin.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 后台路由 → 审计动作码推导：特殊路径精确命中，未登记路由仍有稳定兜底码。 */
class AuditActionResolverTest {

  @Test
  void resolvesWriteOperationsByMethodAndPath() {
    assertEquals("staff.create", AuditActionResolver.resolve("POST", "/admin/staff").action());
    assertEquals("staff.update", AuditActionResolver.resolve("PATCH", "/admin/staff/12/status").action());
    assertEquals("staff.delete", AuditActionResolver.resolve("DELETE", "/admin/staff/12").action());
    assertEquals("staff.unbind", AuditActionResolver.resolve("DELETE", "/admin/staff/12/im-binding").action());
    assertEquals("reservation.confirm", AuditActionResolver.resolve("POST", "/admin/reservations/9/confirm").action());
    assertEquals("reservation.assign_room",
        AuditActionResolver.resolve("POST", "/admin/reservations/9/assign-room").action());
    assertEquals("reservation.no_show",
        AuditActionResolver.resolve("POST", "/admin/reservations/9/no-show").action());
    assertEquals("order.ktv_session.open",
        AuditActionResolver.resolve("POST", "/admin/reservations/9/open-table").action());
    assertEquals("media.image.upload", AuditActionResolver.resolve("POST", "/admin/media/images").action());
    assertEquals("auth.password.change", AuditActionResolver.resolve("POST", "/admin/auth/password").action());
    assertEquals("context.select", AuditActionResolver.resolve("POST", "/admin/context/select").action());
  }

  @Test
  void fallsBackToStableCodeForUnregisteredRoutes() {
    // 储值充值 BFF（/admin/ktv/wallet-recharge）已删除：储值唯一入口是「储值管理」页直连 customer 域，
    // 该路径不再是注册规则，只能落到通用推导码，不再产出 wallet.recharge。
    AuditActionResolver.Resolved removedBffRoute =
        AuditActionResolver.resolve("PUT", "/admin/ktv/wallet-recharge/7");
    assertEquals("ktv.wallet-recharge.update", removedBffRoute.action());
    assertEquals("7", removedBffRoute.resourceId());

    AuditActionResolver.Resolved unknown = AuditActionResolver.resolve("POST", "/admin/unknown-thing/42");
    assertEquals("unknown-thing.create", unknown.action());
    assertEquals("42", unknown.resourceId());
  }

  @Test
  void labelsAreAlwaysChineseOrCode() {
    assertFalse(AuditActionResolver.resolve("POST", "/admin/staff").actionLabel().isBlank());
    assertEquals("结台结算", AuditActionsLabel.of("order.settle"));
  }

  @Test
  void classifiesWriteAndSensitiveRead() {
    assertTrue(AuditActionResolver.isWrite("POST"));
    assertTrue(AuditActionResolver.isWrite("PATCH"));
    assertFalse(AuditActionResolver.isWrite("GET"));

    assertTrue(AuditActionResolver.isSensitiveRead("GET", "/admin/reports/export"));
    assertTrue(AuditActionResolver.isSensitiveRead("GET", "/admin/reports/employee-performance/export"));
    assertTrue(AuditActionResolver.isSensitiveRead("GET", "/admin/media/download"));
    assertFalse(AuditActionResolver.isSensitiveRead("GET", "/admin/reports/employee-performance"));
    assertFalse(AuditActionResolver.isSensitiveRead("POST", "/admin/reports/export"));
  }

  /** 小工具：避免测试直接依赖 SDK 常量类名，语义与 AuditActions.labelOf 一致。 */
  private static final class AuditActionsLabel {
    static String of(String code) {
      return com.gvchat.infrastructure.audit.AuditActions.labelOf(code);
    }
  }
}
