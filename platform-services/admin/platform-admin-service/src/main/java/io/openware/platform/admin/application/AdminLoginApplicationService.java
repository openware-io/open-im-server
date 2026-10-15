package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.platform.admin.domain.model.ConsAdminAccount;
import io.openware.platform.admin.domain.repository.ConsAdminAccountRepository;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.ConsAdminSessionStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** SaaS 后台账号密码登录：校验 BCrypt 后创建 Redis 会话。 */
@Service
public class AdminLoginApplicationService {

    private final ConsAdminAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final ConsAdminSessionStore sessionStore;

    public AdminLoginApplicationService(ConsAdminAccountRepository accountRepository,
                                        PasswordEncoder passwordEncoder,
                                        ConsAdminSessionStore sessionStore) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
    }

    /** 登录：校验用户名/密码（BCrypt），按用户选择的有效期创建 Redis 会话。 */
    public AdminAuthResult login(String username, String password, Duration ttl) {
        ConsAdminAccount account = accountRepository.findByUsername(username)
                .filter(ConsAdminAccount::active)
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
