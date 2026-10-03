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
