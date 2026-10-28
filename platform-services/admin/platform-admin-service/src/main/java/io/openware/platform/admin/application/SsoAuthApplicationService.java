package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.IdaasSsoClient;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.ConsAdminSessionStore;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * SaaS 后台 SSO 交换：用一次性票据经 IDaaS /sso/verify 换到 accountId，
 * 映射到 cons_admin_account，命中则创建 Redis 会话并返回 sessionId；未命中返回 404。
 */
@Service
public class SsoAuthApplicationService {

    private final ConsAdminAccountRepository accountRepository;
    private final IdaasSsoClient idaasSsoClient;
    private final ConsAdminSessionStore sessionStore;
    private final AdminRoleProfileService roleProfileService;

    public SsoAuthApplicationService(ConsAdminAccountRepository accountRepository,
                                     IdaasSsoClient idaasSsoClient,
                                     ConsAdminSessionStore sessionStore) {
        this(accountRepository, idaasSsoClient, sessionStore, null);
    }

    @Autowired
    public SsoAuthApplicationService(ConsAdminAccountRepository accountRepository,
                                     IdaasSsoClient idaasSsoClient,
                                     ConsAdminSessionStore sessionStore,
                                     AdminRoleProfileService roleProfileService) {
        this.accountRepository = accountRepository;
        this.idaasSsoClient = idaasSsoClient;
        this.sessionStore = sessionStore;
        this.roleProfileService = roleProfileService;
    }

    public AdminAuthResult exchange(String ticket, Duration ttl) {
        IdaasSsoClient.SsoUser user = idaasSsoClient.verifyTicket(ticket);
        if (!user.valid() || user.accountId() <= 0) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SSO_TICKET_INVALID", "SSO 票据无效或已过期");
        }
        Duration effectiveTtl = ttl;
        long groupExpiresAt = user.expiresAt();
        if (groupExpiresAt > 0) {
            long remainingMillis = groupExpiresAt - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SSO_TICKET_EXPIRED", "集团会话已过期");
            }
            effectiveTtl = Duration.ofMillis(Math.min(ttl.toMillis(), remainingMillis));
        }
        ConsAdminAccount account = accountRepository.findBySubject(String.valueOf(user.accountId()))
                .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "SaaS_ADMIN_ACCOUNT_NOT_FOUND",
                        "集团账号未关联平台后台账号: " + user.accountId()));
        return toResult(account, effectiveTtl);
    }

    private AdminAuthResult toResult(ConsAdminAccount account, Duration ttl) {
        AdminContext ctx = new AdminContext(account.id(), account.username(), account.displayName(), account.role(),
                account.platformAccountId());
        String sessionId = sessionStore.createSession(ctx, ttl);
        AdminAccountView user = new AdminAccountView(account.id(), account.username(), account.displayName(),
                account.role().name(), account.platformAccountId());
        AdminRoleProfileService.AdminWorkspace workspace = resolveWorkspace(account);
        return new AdminAuthResult(sessionId, user, System.currentTimeMillis() + ttl.toMillis(),
                workspace.roles(), workspace.workspaces(), workspace.defaultWorkspace(), workspace.landing());
    }

    private AdminRoleProfileService.AdminWorkspace resolveWorkspace(ConsAdminAccount account) {
        if (roleProfileService != null) {
            return roleProfileService.resolve(account.platformAccountId(), account.role());
        }
        if (account.role() == AdminRole.TENANT_ADMIN) {
            return new AdminRoleProfileService.AdminWorkspace(List.of("tenant.owner"), List.of("HEADQUARTERS"),
                    "HEADQUARTERS", "/admin/tenant/overview");
        }
        return new AdminRoleProfileService.AdminWorkspace(List.of("platform.operator"), List.of("PLATFORM"),
                "PLATFORM", "/admin/platform/tenants");
    }
}
