package com.gvchat.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gvchat.common.port.TokenProvider;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.platform.identity.application.OAuthImBindApplicationService.BindResult;
import com.gvchat.platform.identity.infra.oauth.ImOAuthClient;
import com.gvchat.platform.identity.infra.oauth.ImOAuthUserInfo;
import com.gvchat.platform.identity.infra.client.TenantServiceClient;
import com.gvchat.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.ProfileSyncRecordMapper;
import com.gvchat.platform.identity.infra.persistence.po.OauthLinkPo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** IM OAuth 绑定：绑定成功/已存在都签发 SaaS JWT（第三方授权统一走签名 Token）。 */
@ExtendWith(MockitoExtension.class)
class OAuthImBindApplicationServiceTest {

  @Mock private OAuthImBindApplicationService self;
  @Mock private ImOAuthClient imOAuthClient;
  @Mock private IdentityAccountMapper accountMapper;
  @Mock private LoginIdentityMapper loginIdentityMapper;
  @Mock private OauthLinkMapper oauthLinkMapper;
  @Mock private ProfileSyncRecordMapper profileSyncRecordMapper;
  @Mock private TokenProvider tokenProvider;
  @Mock private TenantServiceClient tenantServiceClient;

  private JwtProperties jwtProperties;
  private OAuthImBindApplicationService service;

  @BeforeEach
  void setUp() {
    jwtProperties = new JwtProperties();
    jwtProperties.setExpirationMs(60000L);
    service = new OAuthImBindApplicationService(self, imOAuthClient, accountMapper, loginIdentityMapper,
        oauthLinkMapper, profileSyncRecordMapper, tokenProvider, jwtProperties, tenantServiceClient);
  }

  @Test
  void bind_issuesJwtForExistingLink() {
    when(imOAuthClient.fetchUserInfo("im-token"))
        .thenReturn(new ImOAuthUserInfo("open-1", "nick", "avatar", "phone", "scope"));
    OauthLinkPo link = new OauthLinkPo();
    link.setAccountId(9L);
    when(oauthLinkMapper.selectOne(any())).thenReturn(link);
    when(tokenProvider.createAccessToken(9L, "open-1", 0)).thenReturn("signed-jwt");

    BindResult result = service.bind("im-token");

    assertEquals(9L, result.accountId());
    assertEquals("signed-jwt", result.accessToken());
    assertEquals(60L, result.expiresInSeconds());
    assertTrue(result.bound());
  }

  @Test
  void bind_issuesJwtForNewAccount() {
    when(imOAuthClient.fetchUserInfo("im-token"))
        .thenReturn(new ImOAuthUserInfo("open-2", "nick", "avatar", "phone", "scope"));
    when(oauthLinkMapper.selectOne(any())).thenReturn(null);
    when(self.createAccountAndBind(eq("open-2"), any(ImOAuthUserInfo.class))).thenReturn(11L);
    when(tokenProvider.createAccessToken(11L, "open-2", 0)).thenReturn("signed-jwt-2");

    BindResult result = service.bind("im-token");

    assertEquals(11L, result.accountId());
    assertEquals("signed-jwt-2", result.accessToken());
    assertEquals(60L, result.expiresInSeconds());
    assertTrue(result.bound());
  }
}
