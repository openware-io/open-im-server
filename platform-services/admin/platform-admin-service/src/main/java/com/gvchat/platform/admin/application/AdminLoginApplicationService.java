package com.gvchat.platform.admin.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.platform.admin.domain.model.SaaAdminAccount;
import com.gvchat.platform.admin.domain.port.SaaAdminAccountRepository;
import com.gvchat.platform.admin.infra.security.AdminContext;
import com.gvchat.platform.admin.infra.security.SaaAdminSessionStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** SaaS 后台账号密码登录：校验 BCrypt 后创建 Redis 会话。 */
@Service
public class AdminLoginApplicationService {

    private final SaaAdminAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final SaaAdminSessionStore sessionStore;

    public AdminLoginApplicationService(SaaAdminAccountRepository accountRepository,
                                        PasswordEncoder passwordEncoder,
                                        SaaAdminSessionStore sessionStore) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
    }

    /** 登录：校验用户名/密码（BCrypt），按用户选择的有效期创建 Redis 会话。 */
    public AdminAuthResult login(String username, String password, Duration ttl) {
        SaaAdminAccount account = accountRepository.findByUsername(username)
                .filter(SaaAdminAccount::active)
                .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "ADMIN_LOGIN_INVALID",
                        "用户名或密码错误"));
        if (account.passwordHash() == null || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "ADMIN_LOGIN_INVALID", "用户名或密码错误");
        }
        AdminContext ctx = new AdminContext(account.id(), account.username(), account.displayName(), account.role(),
                account.platformAccountId());
        String sessionId = sessionStore.createSession(ctx, ttl);
        AdminAccountView user = new AdminAccountView(account.id(), account.username(), account.displayName(),
                account.role().name(), account.platformAccountId());
        return new AdminAuthResult(sessionId, user, System.currentTimeMillis() + ttl.toMillis());
    }
}
