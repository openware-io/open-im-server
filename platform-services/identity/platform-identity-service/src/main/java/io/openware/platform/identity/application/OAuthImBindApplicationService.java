package io.openware.platform.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.common.port.TokenProvider;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.platform.identity.domain.account.model.IdentityAccount;
import io.openware.platform.identity.domain.account.model.LoginIdentity;
import io.openware.platform.identity.domain.account.model.OauthLink;
import io.openware.platform.identity.domain.account.model.ProfileSyncRecord;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import io.openware.platform.identity.domain.account.repository.LoginIdentityRepository;
import io.openware.platform.identity.domain.account.repository.OauthLinkRepository;
import io.openware.platform.identity.domain.account.repository.ProfileSyncRecordRepository;
import io.openware.platform.identity.infra.oauth.ImOAuthClient;
import io.openware.platform.identity.infra.oauth.ImOAuthUserInfo;
import io.openware.platform.identity.infra.client.TenantServiceClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 用户级授权 → SaaS 身份绑定。
 *
 * <p>流程：用 im_access_token 调 IM /oauth/userinfo 拿 open_id + 资料，
 * 按 open_id 找或建 SaaS 账号，写入 oauth_link + login_identity + profile_sync_record。
 * 幂等：同一 open_id 重复 bind 返回已有账号；provider+provider_account_id 唯一键冲突时查回。</p>
 *
 * <p>持久化经领域端口完成；账号与登录标识的创建走领域工厂，状态与类型由领域固定。
 */
@Service
public class OAuthImBindApplicationService {
    private static final String PROVIDER_IM = "IM";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OAuthImBindApplicationService self;
    private final ImOAuthClient imOAuthClient;
    private final IdentityAccountRepository accountRepository;
    private final LoginIdentityRepository loginIdentityRepository;
    private final OauthLinkRepository oauthLinkRepository;
    private final ProfileSyncRecordRepository profileSyncRecordRepository;
    private final TokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final TenantServiceClient tenantServiceClient;

    public OAuthImBindApplicationService(@Lazy OAuthImBindApplicationService self, ImOAuthClient imOAuthClient,
                                         IdentityAccountRepository accountRepository,
                                         LoginIdentityRepository loginIdentityRepository,
                                         OauthLinkRepository oauthLinkRepository,
                                         ProfileSyncRecordRepository profileSyncRecordRepository,
                                         TokenProvider tokenProvider, JwtProperties jwtProperties,
                                         TenantServiceClient tenantServiceClient) {
        this.self = self;
        this.imOAuthClient = imOAuthClient;
        this.accountRepository = accountRepository;
        this.loginIdentityRepository = loginIdentityRepository;
        this.oauthLinkRepository = oauthLinkRepository;
        this.profileSyncRecordRepository = profileSyncRecordRepository;
        this.tokenProvider = tokenProvider;
        this.jwtProperties = jwtProperties;
        this.tenantServiceClient = tenantServiceClient;
    }

    /** 授权码换 token 后绑定（H5 客户端无 appSecret，授权码交换由服务端完成）。 */
    public BindResult bindByCode(String code, String codeVerifier, String redirectUri, String appId) {
        return bindByCode(code, codeVerifier, redirectUri, appId, null, null);
    }

    public BindResult bindByCode(String code, String codeVerifier, String redirectUri, String appId,
                                 String state, String nonce) {
        if (state != null && state.isBlank() || nonce != null && nonce.isBlank()) {
            throw new ApiException(HttpStatusCodes.BAD_REQUEST, "OIDC_TRANSACTION_INVALID",
                    "state 与 nonce 不能为空");
        }
        String imAccessToken = imOAuthClient.exchangeCode(code, codeVerifier, redirectUri, appId);
        BindResult result = bind(imAccessToken, appId);
        tenantServiceClient.grantConsumerAuthorization(result.accountId(), appId, "profile.basic");
        return result;
    }

    public BindResult bind(String imAccessToken) {
        return bind(imAccessToken, "legacy");
    }

    public BindResult bind(String imAccessToken, String appId) {
        ImOAuthUserInfo info = imOAuthClient.fetchUserInfo(imAccessToken);
        String openId = info.openId();
        if (openId == null || openId.isBlank()) {
            throw new ApiException(HttpStatusCodes.BAD_REQUEST, "IM_OPEN_ID_MISSING", "IM 用户信息缺少 open_id");
        }
        OauthLink existing = findLink(openId, appId);
        if (existing != null) {
            return bindResult(existing.accountId(), openId);
        }
        try {
            Long accountId = self.createAccountAndBind(openId, info, appId);
            return bindResult(accountId, openId);
        } catch (DuplicateKeyException ex) {
            OauthLink raced = findLink(openId, appId);
            if (raced == null) {
                throw new ApiException(HttpStatusCodes.CONFLICT, "IM_BIND_CONFLICT", "IM 账号已被绑定");
            }
            return bindResult(raced.accountId(), openId);
        }
    }

    private BindResult bindResult(Long accountId, String openId) {
        String accessToken = tokenProvider.createAccessToken(accountId, openId, 0);
        long expiresInSeconds = jwtProperties.getExpirationMs() / 1000;
        return new BindResult(accountId, accessToken, expiresInSeconds, true);
    }

    @Transactional
    public Long createAccountAndBind(String openId, ImOAuthUserInfo info) {
        return createAccountAndBind(openId, info, "legacy");
    }

    @Transactional
    public Long createAccountAndBind(String openId, ImOAuthUserInfo info, String appId) {
        LocalDateTime now = LocalDateTime.now();
        IdentityAccount account = accountRepository.save(IdentityAccount.register());

        loginIdentityRepository.save(
                LoginIdentity.forLogin(account.id(), PROVIDER_IM, openId, null));
        oauthLinkRepository.save(
                OauthLink.bind(account.id(), PROVIDER_IM, appId, openId, info.scope()));
        profileSyncRecordRepository.save(new ProfileSyncRecord(
                null, null, account.id(), PROVIDER_IM, toProfileJson(info), now));
        return account.id();
    }

    private OauthLink findLink(String openId, String appId) {
        return oauthLinkRepository.findAllByProviderAccount(PROVIDER_IM, appId, openId).stream()
                .findFirst().orElse(null);
    }

    private String toProfileJson(ImOAuthUserInfo info) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("nickname", info.nickname());
        profile.put("avatar", info.avatar());
        profile.put("phone", info.phone());
        try {
            return OBJECT_MAPPER.writeValueAsString(profile);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize IM profile", ex);
        }
    }

    public record BindResult(Long accountId, String accessToken, long expiresInSeconds, boolean bound) {
    }
}
