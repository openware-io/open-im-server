package com.gvchat.platform.tenant.api.controller;

import com.gvchat.platform.tenant.application.PermissionSnapshotProvider;
import com.gvchat.platform.tenant.domain.authorization.AccountContext;
import com.gvchat.platform.tenant.domain.authorization.PermissionSnapshot;
import com.gvchat.platform.tenant.domain.authorization.RoleBinding;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IAM 内部端点（供 identity-service 调用的内部契约，不走网关）。
 */
@RestController
@RequestMapping("/internal/iam")
public class InternalIamController {

    private final PermissionSnapshotProvider provider;

    public InternalIamController(PermissionSnapshotProvider provider) {
        this.provider = provider;
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
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "consumer authorization required");
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

    /** 账号角色绑定视图（角色名 + 作用域 + 门店名，供运营人员列表展示）。 */
    @GetMapping("/account/{accountId}/role-bindings")
    public List<RoleBinding> roleBindings(@PathVariable Long accountId) {
        return provider.roleBindings(accountId);
    }
}
