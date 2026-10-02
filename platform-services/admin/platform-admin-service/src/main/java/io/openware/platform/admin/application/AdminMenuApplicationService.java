package io.openware.platform.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.admin.api.menu.AdminMenuItem;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import io.openware.platform.admin.infra.persistence.mapper.TenantPaymentMethodMapper;
import io.openware.platform.admin.infra.persistence.mapper.AdminMenuMapper;
import io.openware.platform.admin.infra.persistence.po.AdminMenuPo;
import io.openware.platform.admin.infra.persistence.po.TenantPaymentMethodPo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SaaS 后台菜单 BFF：平台运营后台 + 租户后台菜单聚合。
 * 租户后台菜单按「平台已授权的支付方式」过滤：储值管理仅当授予 payment.method.wallet 时显示。
 */
@Service
public class AdminMenuApplicationService {
    private final TenantPaymentMethodMapper tenantPaymentMethodMapper;
    private final TenantIamDomainClient tenantIamClient;
    private final AdminTenantContextTokenSigner contextTokens;
    private final AdminMenuMapper menuMapper;

    public AdminMenuApplicationService(TenantPaymentMethodMapper tenantPaymentMethodMapper,
                                       TenantIamDomainClient tenantIamClient, AdminTenantContextTokenSigner contextTokens,
                                       AdminMenuMapper menuMapper) {
        this.tenantPaymentMethodMapper = tenantPaymentMethodMapper;
        this.tenantIamClient = tenantIamClient;
        this.contextTokens = contextTokens;
        this.menuMapper = menuMapper;
    }

    public List<AdminMenuItem> menus(String scope) {
        List<AdminMenuPo> rows = menuMapper.selectList(new LambdaQueryWrapper<AdminMenuPo>()
                        .eq(AdminMenuPo::getStatus, "ACTIVE")
                        .orderByAsc(AdminMenuPo::getScopeLevel, AdminMenuPo::getSortNo, AdminMenuPo::getId));
        List<AdminMenuItem> all = menuTree(rows);
        /*
                // —— 平台运营后台 ——
                new AdminMenuItem(1L, 0L, "tenant", "租户管理", "/admin/platform/tenants", "building", "PLATFORM", "core", List.of()),
                new AdminMenuItem(2L, 0L, "pricing", "计价方案", "/admin/pricing-plans", "price", "PLATFORM", "core", List.of()),
                new AdminMenuItem(3L, 0L, "iam", "权限管理", "/admin/iam", "management", "PLATFORM", "core", List.of(
                        new AdminMenuItem(31L, 3L, "iam.role", "角色", "/admin/iam/roles", "avatar", "PLATFORM", "core", List.of()),
                        new AdminMenuItem(32L, 3L, "iam.permission", "权限", "/admin/iam/permissions", "key", "PLATFORM", "core", List.of())
                )),
                new AdminMenuItem(4L, 0L, "paymethod", "支付方式", "/admin/platform/payment-methods", "money", "PLATFORM", "core", List.of()),
                // —— 租户后台 ——
                new AdminMenuItem(10L, 0L, "store", "门店", "/admin/tenant/stores", "shop", "TENANT", "core", List.of()),
                // 币种：租户级单一来源（16_CURRENCY_CONVENTIONS §2）。菜单项本身不带权限码，
                // 前端按 tenant.currency.manage 隐藏/置灰入口（与其它页面一致）；
                // 平台运营改币种 = 先切到目标租户上下文，再走同一入口，故 PLATFORM 段不重复登记。
                new AdminMenuItem(27L, 0L, "currency", "币种", "/admin/tenant/currency", "wallet", "TENANT", "core", List.of()),
                new AdminMenuItem(11L, 0L, "resource", "包厢管理", "/admin/resources", "grid", "TENANT", "core", List.of()),
                new AdminMenuItem(12L, 0L, "reservation", "预约管理", "/business/reservations", "calendar", "TENANT", "core", List.of()),
                // 13：该页实质是「包厢收银台」（房态看板 + 开台/点单/结台/收银），菜单名与页面口径统一；
                // path/code 保持不变，避免菜单授权与前端路由漂移。
                new AdminMenuItem(13L, 0L, "order", "收银台", "/business/orders", "tickets", "TENANT", "core", List.of()),
                // 28：订单管理（列表）——只做查询与处置（详情/取消），现场收银动作留在收银台；紧随收银台之后。
                new AdminMenuItem(28L, 0L, "order-manage", "订单管理", "/admin/orders", "list", "TENANT", "core", List.of()),
                new AdminMenuItem(24L, 0L, "inventory", "仓库管理", "/admin/inventory", "box", "TENANT", "core", List.of()),
                new AdminMenuItem(25L, 0L, "products", "商品管理", "/admin/products", "goods", "TENANT", "core", List.of()),
                new AdminMenuItem(14L, 0L, "payment", "收银/支付", "/business/payments", "credit-card", "TENANT", "core", List.of()),
                new AdminMenuItem(17L, 0L, "shift", "交班/日结", "/business/shifts", "clock", "TENANT", "core", List.of()),
                new AdminMenuItem(15L, 0L, "report", "报表", "/admin/reports", "chart", "TENANT", "core", List.of()),
                // 18：菜单标签按业务口径改为「客户管理」——cst_member 实际存的是客户（等级/权益/成长值未实现）。
                // 路径 /business/members 与 code member 保持不变，避免菜单授权与前端路由漂移。
                new AdminMenuItem(18L, 0L, "member", "客户管理", "/business/members", "user", "TENANT", "core", List.of()),
                new AdminMenuItem(19L, 0L, "points", "积分管理", "/business/points", "star", "TENANT", "core", List.of()),
                new AdminMenuItem(20L, 0L, "paymethod", "支付方式", "/business/payment-methods", "collection", "TENANT", "core", List.of()),
                new AdminMenuItem(21L, 0L, "wallet", "储值管理", "/business/wallet", "coin", "TENANT", "core", List.of()),
                new AdminMenuItem(22L, 0L, "security", "脱敏权限", "/admin/security", "lock", "TENANT", "core", List.of()),
                new AdminMenuItem(16L, 0L, "ktv", "KTV 配置", "/admin/ktv/config", "setting", "TENANT", "ktv", List.of()),
                new AdminMenuItem(23L, 0L, "staff", "运营人员", "/admin/staff", "service", "TENANT", "core", List.of()),
                // 审计日志：平台与租户共用同一页面，可见范围由服务端按上下文强制（平台看全部租户，租户只看本租户）。
                new AdminMenuItem(26L, 0L, "audit", "审计日志", "/admin/audits", "document", "TENANT", "core", List.of())
        );*/
        var admin = AdminContextHolder.get();
        if (admin == null) return List.of();
        List<AdminMenuItem> scoped = all.stream()
                .filter(menu -> scope == null || scope.isBlank()
                        || ("TENANT".equalsIgnoreCase(scope)
                            ? ("TENANT".equalsIgnoreCase(menu.scope()) || "STORE".equalsIgnoreCase(menu.scope()))
                            : scope.equalsIgnoreCase(menu.scope())))
                .filter(menu -> !"PLATFORM".equals(menu.scope())
                        || admin.role() == AdminRole.SUPER_ADMIN || admin.role() == AdminRole.PLATFORM_ADMIN)
                .toList();
        return scoped.stream().anyMatch(menu -> !"PLATFORM".equals(menu.scope()))
                ? filterByContext(scoped, selectedContext())
                : scoped;
    }

    private List<AdminMenuItem> menuTree(List<AdminMenuPo> rows) {
        Map<Long, List<AdminMenuPo>> children = rows.stream()
                .collect(Collectors.groupingBy(AdminMenuPo::getParentId, java.util.LinkedHashMap::new, Collectors.toList()));
        return rows.stream().filter(menu -> menu.getParentId() == 0L)
                .map(menu -> toMenuItem(menu, children)).toList();
    }

    private AdminMenuItem toMenuItem(AdminMenuPo menu, Map<Long, List<AdminMenuPo>> children) {
        List<AdminMenuItem> nested = children.getOrDefault(menu.getId(), List.of()).stream()
                .map(child -> toMenuItem(child, children)).toList();
        return new AdminMenuItem(menu.getId(), menu.getParentId(), menu.getCode(), menu.getName(),
                menu.getPath(), menu.getIcon(), menu.getScopeLevel(), menu.getScopeLevel(),
                menu.getDomainCode(), menu.getBusinessType(), menu.getI18nKey(), menu.getRequiredPermission(),
                menu.getRequiredGrant(), nested);
    }

    /** 先按签名上下文过滤作用域/业态，再按声明式权限剪枝，并删除空父节点。 */
    private List<AdminMenuItem> filterByContext(List<AdminMenuItem> scoped, TenantContext context) {
        if (context == null) return scoped.stream().filter(menu -> !"STORE".equals(menu.scope())).toList();
        var snapshot = tenantIamClient.permissions(context.accountId(), context.tenantId(),
                context.organizationId(), context.storeId());
        List<String> permissions = snapshot == null ? List.of() : snapshot.permissions();
        return scoped.stream().map(menu -> prune(menu, context, permissions)).filter(Objects::nonNull).toList();
    }

    private AdminMenuItem prune(AdminMenuItem menu, TenantContext context, List<String> permissions) {
        if ("STORE".equals(menu.scopeLevel()) && context.storeId() == null) return null;
        if (!matchesBusinessType(menu, context.businessType())) return null;
        if (menu.path() != null && menu.path().isBlank() == false && menu.scopeLevel() != null
                && menu.scopeLevel().equals("STORE") && context.storeId() == null) return null;
        if (menu.requiredPermission() != null && !menu.requiredPermission().isBlank()
                && (permissions == null || !permissions.contains(menu.requiredPermission()))) return null;
        if (menu.requiredGrant() != null && !menu.requiredGrant().isBlank()
                && !isGranted(context.tenantId(), menu.requiredGrant())) return null;
        List<AdminMenuItem> children = menu.children().stream().map(child -> prune(child, context, permissions))
                .filter(Objects::nonNull).toList();
        if (!menu.children().isEmpty() && children.isEmpty() && (menu.path() == null || menu.path().isBlank())) return null;
        return children.equals(menu.children()) ? menu : new AdminMenuItem(menu.id(), menu.parentId(), menu.code(), menu.name(),
                menu.path(), menu.icon(), menu.scope(), menu.scopeLevel(), menu.domainCode(), menu.businessType(),
                menu.i18nKey(), menu.requiredPermission(), menu.requiredGrant(), children);
    }

    /** 业态以服务端签名上下文为准；未声明业态的 core 菜单可见，已声明则按逗号分隔白名单匹配。 */
    private boolean matchesBusinessType(AdminMenuItem menu, String contextBusinessType) {
        String requiredType = menu.businessType();
        if ((requiredType == null || requiredType.isBlank()) && "ktv".equalsIgnoreCase(menu.domainCode())) {
            requiredType = "KTV";
        }
        if (requiredType == null || contextBusinessType == null || contextBusinessType.isBlank()) return true;
        return java.util.Arrays.stream(requiredType.split(","))
                .map(String::trim).anyMatch(type -> type.equalsIgnoreCase(contextBusinessType));
    }

    private TenantContext selectedContext() {
        var admin = AdminContextHolder.get();
        if (admin == null || admin.tenantContextToken() == null) return null;
        TenantContext context = contextTokens.verify(admin.tenantContextToken());
        return java.util.Objects.equals(admin.platformAccountId(), context.accountId()) ? context : null;
    }

    private boolean isGranted(Long tenantId, String grant) {
        if (tenantId == null) return false;
        Long count = tenantPaymentMethodMapper.selectCount(new LambdaQueryWrapper<TenantPaymentMethodPo>()
                .eq(TenantPaymentMethodPo::getTenantId, tenantId)
                .eq(TenantPaymentMethodPo::getMethod, grant)
                .eq(TenantPaymentMethodPo::getGranted, 1));
        return count != null && count > 0;
    }
}
