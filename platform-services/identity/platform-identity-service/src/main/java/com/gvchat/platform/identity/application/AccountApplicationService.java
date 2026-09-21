package com.gvchat.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.port.TokenProvider;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import com.gvchat.platform.identity.infra.persistence.po.IdentityAccountPo;
import com.gvchat.platform.identity.infra.persistence.po.LoginIdentityPo;
import com.gvchat.platform.identity.infra.persistence.po.OauthLinkPo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * SaaS 账号注册/登录（独立于 im-user-service）。
 * 密码用 BCrypt 存储（PasswordEncoder）；登录成功后签发有状态 JWT Access Token（subject=accountId），
 * 替代明文 X-Account-Id 透传（第三方授权/跨服务调用必须校验签名）。
 */
@Service
public class AccountApplicationService {
    private static final String IM_LOGIN_TYPE = "IM";
    private static final String IM_OAUTH_PROVIDER = "IM";

    private final IdentityAccountMapper accountMapper;
    private final LoginIdentityMapper loginIdentityMapper;
    private final OauthLinkMapper oauthLinkMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final JwtProperties jwtProperties;

    public AccountApplicationService(IdentityAccountMapper accountMapper, LoginIdentityMapper loginIdentityMapper,
                                     OauthLinkMapper oauthLinkMapper, PasswordEncoder passwordEncoder,
                                     TokenProvider tokenProvider, JwtProperties jwtProperties) {
        this.accountMapper = accountMapper;
        this.loginIdentityMapper = loginIdentityMapper;
        this.oauthLinkMapper = oauthLinkMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public IdentityAccountPo register(String loginType, String loginIdentifier, String credential) {
        IdentityAccountPo account = new IdentityAccountPo();
        account.setStatus("ACTIVE");
        // 手机号/邮箱注册进入的是普通消费者（客户类型）；员工由管理员在后台开通并标记 EMPLOYEE。
        account.setAccountType("CUSTOMER");
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(account);

        LoginIdentityPo li = new LoginIdentityPo();
        li.setAccountId(account.getId());
        li.setLoginType(loginType);
        li.setLoginIdentifier(loginIdentifier);
        li.setCredential(passwordEncoder.encode(credential));
        li.setStatus("ACTIVE");
        li.setCreatedAt(LocalDateTime.now());
        li.setUpdatedAt(LocalDateTime.now());
        loginIdentityMapper.insert(li);
        return account;
    }

    /**
     * 按登录标识查/建账号（内部端点，供运营人员开通使用）。
     * 已存在则返回并修正 account_type；不存在则按指定类型创建账号 + 登录标识（credential 为空，IM/第三方无密码）。
     * IM 类型额外写入 oauth_link（provider=IM, provider_account_id=open_id），使 OAuth 绑定按 open_id 命中同一账号。
     */
    @Transactional
    public IdentityAccountPo ensureAccount(String loginType, String loginIdentifier, String accountType) {
        LoginIdentityPo li = loginIdentityMapper.selectOne(new QueryWrapper<LoginIdentityPo>()
                .eq("login_type", loginType).eq("login_identifier", loginIdentifier).last("LIMIT 1"));
        if (IM_LOGIN_TYPE.equals(loginType)) {
            OauthLinkPo link = oauthLinkMapper.selectOne(new QueryWrapper<OauthLinkPo>()
                    .eq("provider", IM_OAUTH_PROVIDER).eq("provider_account_id", loginIdentifier).last("LIMIT 1"));
            if (link != null && (!"ACTIVE".equals(link.getStatus())
                    || (li != null && !java.util.Objects.equals(li.getAccountId(), link.getAccountId())))) {
                throw new IllegalStateException("IM identity binding conflict");
            }
            if (li == null && link != null) {
                li = new LoginIdentityPo();
                li.setAccountId(link.getAccountId());
                li.setLoginType(loginType);
                li.setLoginIdentifier(loginIdentifier);
                li.setStatus("ACTIVE");
                li.setCreatedAt(LocalDateTime.now());
                li.setUpdatedAt(li.getCreatedAt());
                loginIdentityMapper.insert(li);
            }
        }
        if (li != null) {
            IdentityAccountPo account = accountMapper.selectById(li.getAccountId());
            if (account == null || !"ACTIVE".equals(account.getStatus()) || !"ACTIVE".equals(li.getStatus())) {
                throw new IllegalStateException("Identity account is not active");
            }
            if (account != null && accountType != null && !accountType.isBlank()
                    && !accountType.equals(account.getAccountType())) {
                account.setAccountType(accountType);
                account.setUpdatedAt(LocalDateTime.now());
                accountMapper.updateById(account);
            }
            ensureImOauthLink(loginType, loginIdentifier, li.getAccountId());
            return account;
        }
        IdentityAccountPo account = new IdentityAccountPo();
        account.setStatus("ACTIVE");
        account.setAccountType(accountType == null || accountType.isBlank() ? "CUSTOMER" : accountType);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(account);

        LoginIdentityPo newLi = new LoginIdentityPo();
        newLi.setAccountId(account.getId());
        newLi.setLoginType(loginType);
        newLi.setLoginIdentifier(loginIdentifier);
        newLi.setStatus("ACTIVE");
        newLi.setCreatedAt(LocalDateTime.now());
        newLi.setUpdatedAt(LocalDateTime.now());
        loginIdentityMapper.insert(newLi);
        ensureImOauthLink(loginType, loginIdentifier, account.getId());
        return account;
    }

    /** IM 账号补写 OAuth 绑定（幂等）：确保 /oauth 授权回调按 open_id 命中本账号而非另建新账号。 */
    private void ensureImOauthLink(String loginType, String loginIdentifier, Long accountId) {
        if (!IM_LOGIN_TYPE.equals(loginType) || accountId == null) {
            return;
        }
        OauthLinkPo existing = oauthLinkMapper.selectOne(new QueryWrapper<OauthLinkPo>()
                .eq("provider", IM_OAUTH_PROVIDER).eq("provider_account_id", loginIdentifier).last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        OauthLinkPo link = new OauthLinkPo();
        link.setAccountId(accountId);
        link.setProvider(IM_OAUTH_PROVIDER);
        link.setProviderAccountId(loginIdentifier);
        link.setStatus("ACTIVE");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        oauthLinkMapper.insert(link);
    }

    public LoginResult login(String loginType, String loginIdentifier, String credential) {
        QueryWrapper<LoginIdentityPo> qw = new QueryWrapper<>();
        qw.eq("login_type", loginType).eq("login_identifier", loginIdentifier).eq("status", "ACTIVE");
        LoginIdentityPo li = loginIdentityMapper.selectOne(qw);
        if (li == null || !passwordEncoder.matches(credential, li.getCredential())) {
            // 登录失败是认证问题，必须回 401 + 稳定错误码，不能作为 IllegalStateException 落成 500。
            throw new ApiException(401, "AUTH_INVALID_CREDENTIAL", "用户名或密码错误");
        }
        IdentityAccountPo account = accountMapper.selectById(li.getAccountId());
        String accessToken = tokenProvider.createAccessToken(account.getId(), loginIdentifier, 0);
        long expiresInSeconds = jwtProperties.getExpirationMs() / 1000;
        return new LoginResult(account.getId(), accessToken, expiresInSeconds);
    }

    /** 登录结果：账号 ID + 签名 JWT Access Token + 有效期（秒）。 */
    public record LoginResult(Long accountId, String accessToken, long expiresInSeconds) {}
}
