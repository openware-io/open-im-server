package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.application.MembershipApplicationService;
import io.openware.platform.tenant.application.PermissionSnapshotProvider;
import io.openware.platform.tenant.application.RoleBindingApplicationService;
import io.openware.platform.tenant.domain.authorization.AccountContext;
import io.openware.platform.tenant.domain.authorization.PermissionSnapshot;
import io.openware.platform.tenant.domain.authorization.RoleBinding;
import io.openware.platform.tenant.domain.membership.Membership;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * IAM 内部端点（供 identity-service 调用的内部契约，不走网关）。
 */
@RestController
@RequestMapping("/internal/iam")
public class InternalIamController {

    private final PermissionSnapshotProvider provider;
    private final RoleBindingApplicationService roleBindings;
    private final MembershipApplicationService memberships;

    public InternalIamController(PermissionSnapshotProvider provider,
                                 RoleBindingApplicationService roleBindings,
                                 MembershipApplicationService memberships) {
        this.provider = provider;
        this.roleBindings = roleBindings;
        this.memberships = memberships;
    }

    /** 账号在某作用域下的权限快照（权限码 + 授权版本）；refresh=true 跳过缓存（上下文选择时用）。 */
    @GetMapping("/account/{accountId}/permissions")
    public PermissionSnapshot permissions(@PathVariable Long accountId,
                                          @RequestParam Long tenantId,
                                          @RequestParam(required = false) Long organizationId,
                                          @RequestParam(required = false) Long storeId,
                                          @RequestParam(required = false) Boolean refresh) {
        return provider.snapshot(accountId, tenantId, organizationId, storeId, Boolean.TRUE.equals(refresh));
    }

    /** 账号可访问的经营上下文列表（按 tenant/organization/store 聚合）。 */
    @GetMapping("/account/{accountId}/contexts")
    public List<AccountContext> contexts(@PathVariable Long accountId) {
        return provider.contexts(accountId);
    }

    @GetMapping("/consumer/{appId}/context")
    public AccountContext consumerContext(@PathVariable String appId, @RequestParam Long accountId) {
        if (provider.authorizedConsumerSnapshot(appId, accountId).authorizationVersion() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "consumer authorization required");
        }
        return provider.consumerContext(appId, accountId);
    }

    @GetMapping("/consumer/{appId}/permissions")
    public PermissionSnapshot consumerPermissions(@PathVariable String appId, @RequestParam Long accountId) {
        return provider.authorizedConsumerSnapshot(appId, accountId);
    }

    @PostMapping("/consumer/{appId}/authorizations")
    public void grantConsumerAuthorization(@PathVariable String appId, @RequestParam Long accountId,
                                           @RequestParam(defaultValue = "profile.basic") String scope) {
        provider.grantConsumerAuthorization(appId, accountId, scope);
    }

    @DeleteMapping("/consumer/{appId}/authorization")
    public void revokeConsumerAuthorization(@PathVariable String appId, @RequestParam Long accountId) {
        provider.revokeConsumerAuthorization(appId, accountId);
    }

    /**
     * C 端首次 OAuth 绑定的身份门禁：在 consumer app 所属租户幂等补齐 CUSTOMER membership。
     * 仅在已有 consumer 授权时调用；会员档案仍由 customer 域首次业务访问时创建。
     */
    @PostMapping("/consumer/{appId}/customer-membership")
    public Membership ensureConsumerCustomerMembership(@PathVariable String appId,
                                                        @RequestParam Long accountId) {
        AccountContext context = provider.consumerContext(appId, accountId);
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "consumer authorization required");
        }
        return memberships.ensureForTenant(context.tenantId(), accountId, context.organizationId(),
                "CUSTOMER", "C" + accountId);
    }

    /** 内部：平台引导租户管理员时按显式 tenantId 绑定角色（service-to-service）。 */
    @PostMapping("/account/{accountId}/roles")
    public void assignBootstrapRole(@PathVariable Long accountId,
                                    @RequestBody BootstrapRoleRequest req) {
        roleBindings.assignForBootstrap(accountId, req.tenantId(), req.roleId(),
                req.scopeType(), req.organizationId(), req.storeId());
    }

    public record BootstrapRoleRequest(Long tenantId, Long roleId, String scopeType,
                                       Long organizationId, Long storeId) {}

    /** 账号角色绑定视图（角色名 + 作用域 + 门店名，供运营人员列表展示）。 */
    @GetMapping("/account/{accountId}/role-bindings")
    public List<RoleBinding> roleBindings(@PathVariable Long accountId) {
        return provider.roleBindings(accountId);
    }
}
