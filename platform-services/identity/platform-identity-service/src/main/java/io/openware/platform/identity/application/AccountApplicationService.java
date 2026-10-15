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

    @Transactional
    public IdentityAccount register(String loginType, String loginIdentifier, String credential) {
        // 状态与类型由领域工厂固定（注册的不变量），凭据编码属基础设施细节，故留在应用层完成后再传入领域。
        IdentityAccount account = accountRepository.save(IdentityAccount.register());

        LoginIdentity identity = LoginIdentity.forLogin(account.id(), loginType, loginIdentifier,
                passwordEncoder.encode(credential));
        loginIdentityRepository.save(identity);
        return account;
    }

    /**
     * 按登录标识查/建账号（内部端点，供运营人员开通使用）。
     * 已存在则返回并修正 account_type；不存在则按指定类型创建账号 + 登录标识（credential 为空，IM/第三方无密码）。
     * IM 类型额外写入 oauth_link（provider=IM, provider_account_id=open_id），使 OAuth 绑定按 open_id 命中同一账号。
     */
    @Transactional
    public IdentityAccount ensureAccount(String loginType, String loginIdentifier, String accountType) {
        LoginIdentity li = loginIdentityRepository
                .findByLoginIdentifier(loginType, loginIdentifier).orElse(null);
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
            ensureImOauthLink(loginType, loginIdentifier, accountId);
            return account;
        }
        IdentityAccount account = accountRepository.save(IdentityAccount.ofType(accountType));
        loginIdentityRepository.save(LoginIdentity.forLogin(account.id(), loginType, loginIdentifier, null));
        ensureImOauthLink(loginType, loginIdentifier, account.id());
        return account;
    }

    /** IM 账号补写 OAuth 绑定（幂等）：确保 /oauth 授权回调按 open_id 命中本账号而非另建新账号。 */
    private void ensureImOauthLink(String loginType, String loginIdentifier, Long accountId) {
        if (!IM_LOGIN_TYPE.equals(loginType) || accountId == null) {
            return;
        }
        if (oauthLinkRepository.findByProviderAccount(IM_OAUTH_PROVIDER, loginIdentifier).isPresent()) {
            return;
        }
        oauthLinkRepository.save(OauthLink.bind(accountId, IM_OAUTH_PROVIDER, loginIdentifier, null));
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
