package io.openware.platform.admin.application;

import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.admin.api.menu.AdminMenuItem;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.menu.readmodel.AdminMenuRow;
import io.openware.platform.admin.domain.menu.repository.AdminMenuRepository;
import io.openware.platform.admin.domain.menu.repository.TenantPaymentGrantRepository;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SaaS 后台菜单 BFF：平台运营后台 + 租户后台菜单聚合。
 * 租户后台菜单按作用域、权限和需显式授权的线上支付能力过滤。
 * 储值与积分是默认开放的基础经营能力，不依赖历史支付授权记录。
 *
 * <p>菜单行经**只读投影**读取（{@link AdminMenuRow}）：菜单是配置数据，不参与状态变更，也不承载授权裁决；
 * 授权声明只作查询结果参与剪枝，裁决权属于 tenant-IAM。
 */
@Service
public class AdminMenuApplicationService {
    private final TenantPaymentGrantRepository paymentGrants;
    private final TenantIamDomainClient tenantIamClient;
    private final AdminTenantContextTokenSigner contextTokens;
    private final AdminMenuRepository menuRepository;

    public AdminMenuApplicationService(TenantPaymentGrantRepository paymentGrants,
                                       TenantIamDomainClient tenantIamClient, AdminTenantContextTokenSigner contextTokens,
                                       AdminMenuRepository menuRepository) {
        this.paymentGrants = paymentGrants;
        this.tenantIamClient = tenantIamClient;
        this.contextTokens = contextTokens;
        this.menuRepository = menuRepository;
    }

    public List<AdminMenuItem> menus(String scope) {
        List<AdminMenuItem> all = menuTree(menuRepository.findActiveOrdered());
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

    private List<AdminMenuItem> menuTree(List<AdminMenuRow> rows) {
        Map<Long, List<AdminMenuRow>> children = rows.stream()
                .collect(Collectors.groupingBy(AdminMenuRow::parentId, java.util.LinkedHashMap::new, Collectors.toList()));
        return rows.stream().filter(menu -> menu.parentId() == 0L)
                .map(menu -> toMenuItem(menu, children)).toList();
    }

    private AdminMenuItem toMenuItem(AdminMenuRow menu, Map<Long, List<AdminMenuRow>> children) {
        List<AdminMenuItem> nested = children.getOrDefault(menu.id(), List.of()).stream()
                .map(child -> toMenuItem(child, children)).toList();
        return new AdminMenuItem(menu.id(), menu.parentId(), menu.code(), menu.name(),
                menu.path(), menu.icon(), menu.scopeLevel(), menu.scopeLevel(),
                menu.domainCode(), menu.businessType(), menu.i18nKey(), menu.requiredPermission(),
                menu.requiredGrant(), nested);
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
        return paymentGrants.isGranted(tenantId, grant);
    }
}
