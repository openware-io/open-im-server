package io.openware.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.common.port.TokenProvider;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.platform.identity.infra.oauth.ImOAuthClient;
import io.openware.platform.identity.infra.oauth.ImOAuthUserInfo;
import io.openware.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import io.openware.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import io.openware.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import io.openware.platform.identity.infra.persistence.mapper.ProfileSyncRecordMapper;
import io.openware.platform.identity.infra.persistence.po.IdentityAccountPo;
import io.openware.platform.identity.infra.persistence.po.LoginIdentityPo;
import io.openware.platform.identity.infra.persistence.po.OauthLinkPo;
import io.openware.platform.identity.infra.persistence.po.ProfileSyncRecordPo;
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
 */
@Service
public class OAuthImBindApplicationService {
    private static final String PROVIDER_IM = "IM";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OAuthImBindApplicationService self;
    private final ImOAuthClient imOAuthClient;
    private final IdentityAccountMapper accountMapper;
    private final LoginIdentityMapper loginIdentityMapper;
    private final OauthLinkMapper oauthLinkMapper;
    private final ProfileSyncRecordMapper profileSyncRecordMapper;
    private final TokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final TenantServiceClient tenantServiceClient;

    public OAuthImBindApplicationService(@Lazy OAuthImBindApplicationService self, ImOAuthClient imOAuthClient,
                                         IdentityAccountMapper accountMapper, LoginIdentityMapper loginIdentityMapper,
                                         OauthLinkMapper oauthLinkMapper, ProfileSyncRecordMapper profileSyncRecordMapper,
                                         TokenProvider tokenProvider, JwtProperties jwtProperties, TenantServiceClient tenantServiceClient) {
        this.self = self;
        this.imOAuthClient = imOAuthClient;
        this.accountMapper = accountMapper;
        this.loginIdentityMapper = loginIdentityMapper;
        this.oauthLinkMapper = oauthLinkMapper;
        this.profileSyncRecordMapper = profileSyncRecordMapper;
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
        BindResult result = bind(imAccessToken);
        tenantServiceClient.grantConsumerAuthorization(result.accountId(), appId, "profile.basic");
        return result;
    }

    public BindResult bind(String imAccessToken) {
        ImOAuthUserInfo info = imOAuthClient.fetchUserInfo(imAccessToken);
        String openId = info.openId();
        if (openId == null || openId.isBlank()) {
            throw new ApiException(HttpStatusCodes.BAD_REQUEST, "IM_OPEN_ID_MISSING", "IM 用户信息缺少 open_id");
        }
        OauthLinkPo existing = findLink(openId);
        if (existing != null) {
            return bindResult(existing.getAccountId(), openId);
        }
        try {
            Long accountId = self.createAccountAndBind(openId, info);
            return bindResult(accountId, openId);
        } catch (DuplicateKeyException ex) {
            OauthLinkPo raced = findLink(openId);
            if (raced == null) {
                throw new ApiException(HttpStatusCodes.CONFLICT, "IM_BIND_CONFLICT", "IM 账号已被绑定");
            }
            return bindResult(raced.getAccountId(), openId);
        }
    }

    private BindResult bindResult(Long accountId, String openId) {
        String accessToken = tokenProvider.createAccessToken(accountId, openId, 0);
        long expiresInSeconds = jwtProperties.getExpirationMs() / 1000;
        return new BindResult(accountId, accessToken, expiresInSeconds, true);
    }

    @Transactional
    public Long createAccountAndBind(String openId, ImOAuthUserInfo info) {
        LocalDateTime now = LocalDateTime.now();
        IdentityAccountPo account = new IdentityAccountPo();
        account.setStatus("ACTIVE");
        // IM OAuth 授权进入的默认是普通用户/潜在消费者（客户类型）；员工须由管理员在后台开通并标记 EMPLOYEE。
        account.setAccountType("CUSTOMER");
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);

        LoginIdentityPo loginIdentity = new LoginIdentityPo();
        loginIdentity.setAccountId(account.getId());
        loginIdentity.setLoginType(PROVIDER_IM);
        loginIdentity.setLoginIdentifier(openId);
        loginIdentity.setStatus("ACTIVE");
        loginIdentity.setCreatedAt(now);
        loginIdentity.setUpdatedAt(now);
        loginIdentityMapper.insert(loginIdentity);

        OauthLinkPo link = new OauthLinkPo();
        link.setAccountId(account.getId());
        link.setProvider(PROVIDER_IM);
        link.setProviderAccountId(openId);
        link.setScope(info.scope());
        link.setStatus("ACTIVE");
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        oauthLinkMapper.insert(link);

        ProfileSyncRecordPo record = new ProfileSyncRecordPo();
        record.setAccountId(account.getId());
        record.setProvider(PROVIDER_IM);
        record.setProfileJson(toProfileJson(info));
        record.setOccurredAt(now);
        profileSyncRecordMapper.insert(record);
        return account.getId();
    }

    private OauthLinkPo findLink(String openId) {
        QueryWrapper<OauthLinkPo> qw = new QueryWrapper<>();
        qw.eq("provider", PROVIDER_IM).eq("provider_account_id", openId);
        return oauthLinkMapper.selectOne(qw);
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
