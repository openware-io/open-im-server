package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.platform.admin.domain.model.SaaAdminAccount;
import io.openware.platform.admin.domain.port.SaaAdminAccountRepository;
import io.openware.platform.admin.infra.IdaasSsoClient;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.SaaAdminSessionStore;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * SaaS 后台 SSO 交换：用一次性票据经 IDaaS /sso/verify 换到 accountId，
 * 映射到 saa_admin_account，命中则创建 Redis 会话并返回 sessionId；未命中返回 404。
 */
@Service
public class SsoAuthApplicationService {

    private final SaaAdminAccountRepository accountRepository;
    private final IdaasSsoClient idaasSsoClient;
    private final SaaAdminSessionStore sessionStore;

    public SsoAuthApplicationService(SaaAdminAccountRepository accountRepository,
                                     IdaasSsoClient idaasSsoClient,
                                     SaaAdminSessionStore sessionStore) {
        this.accountRepository = accountRepository;
        this.idaasSsoClient = idaasSsoClient;
        this.sessionStore = sessionStore;
    }

    /**
     * 交换：一次性票据 → Redis 会话。
     *
     * <p><b>有效期继承</b>：{@code ttl} 是本后台自己的上限，实际会话时长取
     * {@code min(ttl, 集团会话剩余时长)}——集团统一登录门户选的「登录有效时长」就是这么带进后台的。
     *
     * <p><b>顺序</b>：先判票据有效与**是否过期**（fail closed：过期票据不查库、也不泄露账号是否已映射），
     * 再按 idaas_subject 映射本后台账号。
     */
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
        SaaAdminAccount account = accountRepository.findBySubject(String.valueOf(user.accountId()))
                .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "SaaS_ADMIN_ACCOUNT_NOT_FOUND",
                        "集团账号未关联平台后台账号: " + user.accountId()));
        return toResult(account, effectiveTtl);
    }

    private AdminAuthResult toResult(SaaAdminAccount account, Duration ttl) {
        AdminContext ctx = new AdminContext(account.id(), account.username(), account.displayName(), account.role(),
                account.platformAccountId());
        String sessionId = sessionStore.createSession(ctx, ttl);
        AdminAccountView user = new AdminAccountView(account.id(), account.username(), account.displayName(),
                account.role().name(), account.platformAccountId());
        return new AdminAuthResult(sessionId, user, System.currentTimeMillis() + ttl.toMillis());
    }
}
