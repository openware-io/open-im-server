package io.openware.platform.identity.application;

import io.openware.common.exception.ApiException;
import io.openware.common.port.TokenProvider;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.platform.identity.domain.account.model.IdentityAccount;
import io.openware.platform.identity.domain.account.model.LoginIdentity;
import io.openware.platform.identity.domain.account.model.OauthLink;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import io.openware.platform.identity.domain.account.repository.LoginIdentityRepository;
import io.openware.platform.identity.domain.account.repository.OauthLinkRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SaaS 账号注册/登录（独立于 im-user-service）。
 * 密码用 BCrypt 存储（PasswordEncoder）；登录成功后签发有状态 JWT Access Token（subject=accountId），
 * 替代明文 X-Account-Id 透传（第三方授权/跨服务调用必须校验签名）。
 */
@Service
public class AccountApplicationService {
    private static final String IM_LOGIN_TYPE = "IM";
    private static final String IM_OAUTH_PROVIDER = "IM";

    private final IdentityAccountRepository accountRepository;
    private final LoginIdentityRepository loginIdentityRepository;
    private final OauthLinkRepository oauthLinkRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final JwtProperties jwtProperties;

    public AccountApplicationService(IdentityAccountRepository accountRepository,
                                     LoginIdentityRepository loginIdentityRepository,
                                     OauthLinkRepository oauthLinkRepository, PasswordEncoder passwordEncoder,
                                     TokenProvider tokenProvider, JwtProperties jwtProperties) {
        this.accountRepository = accountRepository;
        this.loginIdentityRepository = loginIdentityRepository;
        this.oauthLinkRepository = oauthLinkRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
    }

    /** 内部账号只读视图：供 tenant/admin 在平台角色授予前校验账号类型与状态。 */
    public AccountView findAccountView(Long accountId) {
        return accountRepository.findById(accountId)
                .map(account -> new AccountView(account.id(), account.accountType(), account.status()))
                .orElse(null);
    }

    public record AccountView(Long id, String accountType, String status) {
    }

    @Transactional
    public IdentityAccount register(String loginType, String loginIdentifier, String credential) {
        // 状态与类型由领域工厂固定（注册的不变量），凭据编码属基础设施细节，故留在应用层完成后再传入领域。
        IdentityAccount account = accountRepository.save(IdentityAccount.register());

        LoginIdentity identity = LoginIdentity.forLogin(account.id(), loginType, loginIdentifier,
                passwordEncoder.encode(credential));
        loginIdentityRepository.save(identity);
        return account;
    }

    /** 为已有员工账号绑定可密码登录的标识（内部端点；同一标识已存在则拒绝，避免覆盖凭据）。 */
    @Transactional
    public void bindPasswordLogin(Long accountId, String loginType, String loginIdentifier, String credential) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId is required");
        }
        accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Identity account not found: " + accountId))
                .ensureActive();
        var existing = loginIdentityRepository.findUniqueByLoginIdentifier(loginType, loginIdentifier);
        if (existing.isPresent()) {
            if (accountId.equals(existing.get().accountId())) {
                return;
            }
            throw new IllegalStateException("Login identity already exists: " + loginType + "/" + loginIdentifier);
        }
        loginIdentityRepository.save(LoginIdentity.forLogin(accountId, loginType, loginIdentifier,
                passwordEncoder.encode(credential)));
    }

    /** 内部开通：设置/重设 C 端手机号或邮箱密码。 */
    @Transactional
    public void resetPassword(Long accountId, String loginType, String loginIdentifier, String credential) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId is required");
        }
        accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Identity account not found: " + accountId))
                .ensureActive();
        LoginIdentity existing = loginIdentityRepository.findUniqueByLoginIdentifier(loginType, loginIdentifier)
                .orElse(null);
        String encoded = passwordEncoder.encode(credential);
        if (existing != null) {
            if (!accountId.equals(existing.accountId())) {
                throw new IllegalStateException("Login identity already exists: " + loginType + "/" + loginIdentifier);
            }
            loginIdentityRepository.save(new LoginIdentity(existing.id(), accountId, loginType,
                    loginIdentifier, encoded, LoginIdentity.STATUS_ACTIVE));
            return;
        }
        loginIdentityRepository.save(LoginIdentity.forLogin(accountId, loginType, loginIdentifier, encoded));
    }

    /**
     * 按登录标识查/建账号（内部端点，供运营人员开通使用）。
     * 已存在则返回并修正 account_type；不存在则按指定类型创建账号 + 登录标识（credential 为空，IM/第三方无密码）。
     * IM 的 app 维度 oauth_link 由 OAuth 回调按 app_id 写入，本入口不再写 legacy 绑定。
     */
    @Transactional
    public IdentityAccount ensureAccount(String loginType, String loginIdentifier, String accountType) {
        LoginIdentity li = loginIdentityRepository
                .findUniqueByLoginIdentifier(loginType, loginIdentifier).orElse(null);
        if (IM_LOGIN_TYPE.equals(loginType)) {
            OauthLink link = oauthLinkRepository
                    .findByProviderAccount(IM_OAUTH_PROVIDER, loginIdentifier).orElse(null);
            // 冲突只在两种情况成立：绑定不可用，或绑定归属与既有登录标识不一致。
            // 登录标识尚不存在时不算冲突——那正是"按 open_id 补建登录标识"的正常路径。
            if (link != null && (!link.isActive()
                    || (li != null && !link.belongsTo(li.accountId())))) {
                throw new IllegalStateException("IM identity binding conflict");
            }
            if (li == null && link != null) {
                li = loginIdentityRepository.save(
                        LoginIdentity.forLogin(link.accountId(), loginType, loginIdentifier, null));
            }
        }
        if (li != null) {
            final Long accountId = li.accountId();
            IdentityAccount account = accountRepository.findById(accountId).orElse(null);
            if (account == null) {
                throw new IllegalStateException("Identity account is not active");
            }
            account.ensureActive();
            li.ensureActive();
            if (account.hasDifferentAccountType(accountType)) {
                account = accountRepository.save(account.changeAccountType(accountType));
            }
            return account;
        }
        IdentityAccount account = accountRepository.save(IdentityAccount.ofType(accountType));
        loginIdentityRepository.save(LoginIdentity.forLogin(account.id(), loginType, loginIdentifier, null));
        return account;
    }

    public LoginResult login(String loginType, String loginIdentifier, String credential) {
        // 仅可用登录标识可登录：状态过滤收在端口方法里，应用层不再拼接查询条件。
        LoginIdentity identity = loginIdentityRepository
                .findActiveByLoginIdentifier(loginType, loginIdentifier).orElse(null);
        if (identity == null || !passwordEncoder.matches(credential, identity.credential())) {
            // 登录失败是认证问题，必须回 401 + 稳定错误码，不能作为 IllegalStateException 落成 500。
            throw new ApiException(401, "AUTH_INVALID_CREDENTIAL", "用户名或密码错误");
        }
        final Long accountId = identity.accountId();
        IdentityAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(401, "AUTH_INVALID_CREDENTIAL", "用户名或密码错误"));
        String accessToken = tokenProvider.createAccessToken(account.id(), loginIdentifier, 0);
        long expiresInSeconds = jwtProperties.getExpirationMs() / 1000;
        return new LoginResult(account.id(), accessToken, expiresInSeconds);
    }

    /** 登录结果：账号 ID + 签名 JWT Access Token + 有效期（秒）。 */
    public record LoginResult(Long accountId, String accessToken, long expiresInSeconds) {}
}
