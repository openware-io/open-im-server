package com.gvchat.platform.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.platform.admin.api.menu.AdminMenuItem;
import com.gvchat.platform.admin.domain.model.AdminRole;
import com.gvchat.platform.admin.infra.TenantIamDomainClient;
import com.gvchat.platform.admin.infra.security.AdminContextHolder;
import com.gvchat.platform.admin.infra.security.AdminTenantContextTokenSigner;
import com.gvchat.platform.admin.infra.persistence.mapper.TenantPaymentMethodMapper;
import com.gvchat.platform.admin.infra.persistence.po.TenantPaymentMethodPo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SaaS 后台菜单 BFF：平台运营后台 + 租户后台菜单聚合。
 * 租户后台菜单按「平台已授权的支付方式」过滤：储值管理仅当授予 payment.method.wallet 时显示。
 */
@Service
public class AdminMenuApplicationService {
    private final TenantPaymentMethodMapper tenantPaymentMethodMapper;
    private final TenantIamDomainClient tenantIamClient;
    private final AdminTenantContextTokenSigner contextTokens;

    public AdminMenuApplicationService(TenantPaymentMethodMapper tenantPaymentMethodMapper,
                                       TenantIamDomainClient tenantIamClient, AdminTenantContextTokenSigner contextTokens) {
        this.tenantPaymentMethodMapper = tenantPaymentMethodMapper;
        this.tenantIamClient = tenantIamClient;
        this.contextTokens = contextTokens;
    }

    public List<AdminMenuItem> menus(String scope) {
        List<AdminMenuItem> all = List.of(
                // —— 平台运营后台 ——
                new AdminMenuItem(1L, 0L, "tenant", "租户管理", "/admin/platform/tenants", "building", "PLATFORM", List.of()),
                new AdminMenuItem(2L, 0L, "pricing", "计价方案", "/admin/pricing-plans", "coin", "PLATFORM", List.of()),
                new AdminMenuItem(3L, 0L, "iam", "权限管理", "/admin/iam", "key", "PLATFORM", List.of(
                        new AdminMenuItem(31L, 3L, "iam.role", "角色", "/admin/iam/roles", "user", "PLATFORM", List.of()),
                        new AdminMenuItem(32L, 3L, "iam.permission", "权限", "/admin/iam/permissions", "lock", "PLATFORM", List.of())
                )),
                new AdminMenuItem(4L, 0L, "paymethod", "支付方式", "/admin/platform/payment-methods", "money", "PLATFORM", List.of()),
                // —— 租户后台 ——
                new AdminMenuItem(10L, 0L, "store", "门店", "/admin/tenant/stores", "shop", "TENANT", List.of()),
                // 币种：租户级单一来源（16_CURRENCY_CONVENTIONS §2）。菜单项本身不带权限码，
                // 前端按 tenant.currency.manage 隐藏/置灰入口（与其它页面一致）；
                // 平台运营改币种 = 先切到目标租户上下文，再走同一入口，故 PLATFORM 段不重复登记。
                new AdminMenuItem(27L, 0L, "currency", "币种", "/admin/tenant/currency", "money", "TENANT", List.of()),
                new AdminMenuItem(11L, 0L, "resource", "包厢管理", "/admin/resources", "grid", "TENANT", List.of()),
                new AdminMenuItem(12L, 0L, "reservation", "预约管理", "/business/reservations", "calendar", "TENANT", List.of()),
                // 13：该页实质是「包厢收银台」（房态看板 + 开台/点单/结台/收银），菜单名与页面口径统一；
                // path/code 保持不变，避免菜单授权与前端路由漂移。
                new AdminMenuItem(13L, 0L, "order", "收银台", "/business/orders", "list", "TENANT", List.of()),
                // 28：订单管理（列表）——只做查询与处置（详情/取消），现场收银动作留在收银台；紧随收银台之后。
                new AdminMenuItem(28L, 0L, "order-manage", "订单管理", "/admin/orders", "list", "TENANT", List.of()),
                new AdminMenuItem(24L, 0L, "inventory", "仓库管理", "/admin/inventory", "box", "TENANT", List.of()),
                new AdminMenuItem(25L, 0L, "products", "商品管理", "/admin/products", "goods", "TENANT", List.of()),
                new AdminMenuItem(14L, 0L, "payment", "收银/支付", "/business/payments", "money", "TENANT", List.of()),
                new AdminMenuItem(17L, 0L, "shift", "交班/日结", "/business/shifts", "clock", "TENANT", List.of()),
                new AdminMenuItem(15L, 0L, "report", "报表", "/admin/reports", "chart", "TENANT", List.of()),
                // 18：菜单标签按业务口径改为「客户管理」——cst_member 实际存的是客户（等级/权益/成长值未实现）。
                // 路径 /business/members 与 code member 保持不变，避免菜单授权与前端路由漂移。
                new AdminMenuItem(18L, 0L, "member", "客户管理", "/business/members", "user", "TENANT", List.of()),
                new AdminMenuItem(19L, 0L, "points", "积分管理", "/business/points", "star", "TENANT", List.of()),
                new AdminMenuItem(20L, 0L, "paymethod", "支付方式", "/business/payment-methods", "money", "TENANT", List.of()),
                new AdminMenuItem(21L, 0L, "wallet", "储值管理", "/business/wallet", "coin", "TENANT", List.of()),
                new AdminMenuItem(22L, 0L, "security", "脱敏权限", "/admin/security", "lock", "TENANT", List.of()),
                new AdminMenuItem(16L, 0L, "ktv", "KTV 配置", "/admin/ktv/config", "setting", "TENANT", List.of()),
                new AdminMenuItem(23L, 0L, "staff", "运营人员", "/admin/staff", "user", "TENANT", List.of()),
                // 审计日志：平台与租户共用同一页面，可见范围由服务端按上下文强制（平台看全部租户，租户只看本租户）。
                new AdminMenuItem(26L, 0L, "audit", "审计日志", "/admin/audits", "list", "TENANT", List.of())
        );
        var admin = AdminContextHolder.get();
        if (admin == null) return List.of();
        List<AdminMenuItem> scoped = all.stream()
                .filter(menu -> scope == null || scope.isBlank() || scope.equalsIgnoreCase(menu.scope()))
                .filter(menu -> !"PLATFORM".equals(menu.scope())
                        || admin.role() == AdminRole.SUPER_ADMIN || admin.role() == AdminRole.PLATFORM_ADMIN)
                .toList();
        if (scoped.stream().noneMatch(menu -> "TENANT".equals(menu.scope()))) return scoped;
        return filterTenantMenus(scoped, selectedContext());
    }

    /** 租户后台菜单按授权过滤：储值管理（payment.method.wallet）、脱敏权限（iam.role.manage）、审计（audit.view）。 */
    private List<AdminMenuItem> filterTenantMenus(List<AdminMenuItem> scoped, TenantContext context) {
        if (context == null) return scoped.stream().filter(menu -> !"TENANT".equals(menu.scope())).toList();
        boolean walletGranted = isWalletGranted(context.tenantId());
        List<String> permissions = tenantIamClient.permissions(context.accountId(), context.tenantId(),
                context.organizationId(), context.storeId()).permissions();
        boolean canManageRoles = permissions != null && permissions.contains("iam.role.manage");
        boolean canViewAudit = permissions != null && permissions.contains("audit.view");
        return scoped.stream()
                .filter(m -> !"/business/wallet".equals(m.path()) || walletGranted)
                .filter(m -> !"/admin/security".equals(m.path()) || canManageRoles)
                .filter(m -> !"/admin/staff".equals(m.path()) || canManageRoles)
                .filter(m -> !"/admin/audits".equals(m.path()) || canViewAudit)
                .toList();
    }

    private TenantContext selectedContext() {
        var admin = AdminContextHolder.get();
        if (admin == null || admin.tenantContextToken() == null) return null;
        TenantContext context = contextTokens.verify(admin.tenantContextToken());
        return java.util.Objects.equals(admin.platformAccountId(), context.accountId()) ? context : null;
    }

    private boolean isWalletGranted(Long tenantId) {
        if (tenantId == null) return false;
        Long count = tenantPaymentMethodMapper.selectCount(new LambdaQueryWrapper<TenantPaymentMethodPo>()
                .eq(TenantPaymentMethodPo::getTenantId, tenantId)
                .eq(TenantPaymentMethodPo::getMethod, "WALLET")
                .eq(TenantPaymentMethodPo::getGranted, 1));
        return count != null && count > 0;
    }
}
