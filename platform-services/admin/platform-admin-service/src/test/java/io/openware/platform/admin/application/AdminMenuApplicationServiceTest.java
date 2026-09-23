package io.openware.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.admin.api.menu.AdminMenuItem;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.persistence.mapper.TenantPaymentMethodMapper;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 菜单 BFF：租户后台「币种」入口登记（16_CURRENCY_CONVENTIONS §2——平台运营改币种 = 切到目标租户上下文后
 * 走同一入口，故 PLATFORM 段不得重复登记）；同时守住 TENANT 菜单的授权过滤不误删该入口。
 */
class AdminMenuApplicationServiceTest {
  private static final String CURRENCY_PATH = "/admin/tenant/currency";

  private final TenantPaymentMethodMapper tenantPaymentMethodMapper = mock(TenantPaymentMethodMapper.class);
  private final TenantIamDomainClient tenantIamClient = mock(TenantIamDomainClient.class);
  private final AdminTenantContextTokenSigner contextTokens = mock(AdminTenantContextTokenSigner.class);
  private final AdminMenuApplicationService service =
      new AdminMenuApplicationService(tenantPaymentMethodMapper, tenantIamClient, contextTokens);

  @AfterEach
  void cleanup() {
    AdminContextHolder.clear();
  }

  @Test
  void tenantMenusContainCurrencyEntryNearStore() {
    adminWithTenantContext();
    // 未授予储值支付方式、无 iam.role.manage/audit.view：币种入口仍必须返回（它不经授权过滤）。
    when(tenantPaymentMethodMapper.selectCount(any())).thenReturn(0L);
    when(tenantIamClient.permissions(1L, 100L, null, null))
        .thenReturn(new TenantIamDomainClient.PermissionSnapshot(1L, 100L, null, null, 1, List.of()));

    List<AdminMenuItem> menus = service.menus("TENANT");

    AdminMenuItem currency = menus.stream()
        .filter(menu -> CURRENCY_PATH.equals(menu.path()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("TENANT 菜单缺少币种入口: " + CURRENCY_PATH));
    assertEquals(27L, currency.id().longValue());
    assertEquals("currency", currency.code());
    assertEquals("币种", currency.name());
    assertEquals("TENANT", currency.scope());
    assertEquals("money", currency.icon());
    assertEquals(0L, currency.parentId().longValue());
    assertTrue(currency.children().isEmpty());
    // 紧邻「门店」，保持列表可读。
    int storeIndex = indexOf(menus, "/admin/tenant/stores");
    assertTrue(storeIndex >= 0 && menus.indexOf(currency) == storeIndex + 1);
    // 菜单项不带权限码：前端按 tenant.currency.manage 隐藏入口（后端菜单模型无权限字段）。
  }

  @Test
  void platformMenusDoNotContainCurrencyEntry() {
    adminWithTenantContext();

    List<AdminMenuItem> menus = service.menus("PLATFORM");

    assertFalse(menus.isEmpty());
    assertTrue(menus.stream().noneMatch(menu -> CURRENCY_PATH.equals(menu.path())));
    assertTrue(menus.stream().allMatch(menu -> "PLATFORM".equals(menu.scope())));
  }

  /**
   * 「订单/KTV」实质是包厢收银台：菜单名改为「收银台」，并在其**之后**新增「订单管理」（列表页）。
   * 守住三件事：标签、路径不变（避免授权/路由漂移）、新旧两项的相邻顺序。
   */
  @Test
  void tenantMenusRenameCashierAndAddOrderManagementRightAfterIt() {
    adminWithTenantContext();
    when(tenantPaymentMethodMapper.selectCount(any())).thenReturn(0L);
    when(tenantIamClient.permissions(1L, 100L, null, null))
        .thenReturn(new TenantIamDomainClient.PermissionSnapshot(1L, 100L, null, null, 1, List.of()));

    List<AdminMenuItem> menus = service.menus("TENANT");

    AdminMenuItem cashier = menus.stream()
        .filter(menu -> "/business/orders".equals(menu.path()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("TENANT 菜单缺少收银台入口: /business/orders"));
    assertEquals("收银台", cashier.name());
    assertEquals(13L, cashier.id().longValue());
    assertEquals("order", cashier.code());

    AdminMenuItem orderManagement = menus.stream()
        .filter(menu -> "/admin/orders".equals(menu.path()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("TENANT 菜单缺少订单管理入口: /admin/orders"));
    assertEquals("订单管理", orderManagement.name());
    assertEquals(28L, orderManagement.id().longValue());
    assertEquals("order-manage", orderManagement.code());
    assertEquals("TENANT", orderManagement.scope());

    // 订单管理紧跟在收银台之后。
    int cashierIndex = menus.indexOf(cashier);
    assertEquals(cashierIndex + 1, menus.indexOf(orderManagement),
        "订单管理必须排在收银台之后: " + menus.stream().map(AdminMenuItem::name).toList());
  }

  @Test
  void fullMenuListPublishesCurrencyExactlyOnce() {
    adminWithTenantContext();
    when(tenantPaymentMethodMapper.selectCount(any())).thenReturn(1L);
    when(tenantIamClient.permissions(1L, 100L, null, null))
        .thenReturn(new TenantIamDomainClient.PermissionSnapshot(
            1L, 100L, null, null, 1, List.of("iam.role.manage", "audit.view")));

    List<AdminMenuItem> menus = service.menus(null);

    assertEquals(1, menus.stream().filter(menu -> CURRENCY_PATH.equals(menu.path())).count());
  }

  private void adminWithTenantContext() {
    AdminContextHolder.set(new AdminContext(1L, "admin", "运营管理员", AdminRole.PLATFORM_ADMIN, 1L, "token"));
    when(contextTokens.verify("token")).thenReturn(new TenantContext(100L, null, null, 1L, 1));
  }

  private static int indexOf(List<AdminMenuItem> menus, String path) {
    for (int i = 0; i < menus.size(); i++) {
      if (path.equals(menus.get(i).path())) return i;
    }
    return -1;
  }
}
