package io.openware.platform.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.openware.common.port.TokenProvider;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.platform.identity.application.OAuthImBindApplicationService.BindResult;
import io.openware.platform.identity.domain.account.model.OauthLink;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import io.openware.platform.identity.domain.account.repository.LoginIdentityRepository;
import io.openware.platform.identity.domain.account.repository.OauthLinkRepository;
import io.openware.platform.identity.domain.account.repository.ProfileSyncRecordRepository;
import io.openware.platform.identity.infra.oauth.ImOAuthClient;
import io.openware.platform.identity.infra.oauth.ImOAuthUserInfo;
import io.openware.platform.identity.infra.client.TenantServiceClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IM OAuth 绑定：绑定成功/已存在都签发 SaaS JWT（第三方授权统一走签名 Token）。
 *
 * <p>替身是领域端口：测试不再依赖 MyBatis mapper 与持久化对象。
 */
@ExtendWith(MockitoExtension.class)
class OAuthImBindApplicationServiceTest {

  @Mock private OAuthImBindApplicationService self;
  @Mock private ImOAuthClient imOAuthClient;
  @Mock private IdentityAccountRepository accountRepository;
  @Mock private LoginIdentityRepository loginIdentityRepository;
  @Mock private OauthLinkRepository oauthLinkRepository;
  @Mock private ProfileSyncRecordRepository profileSyncRecordRepository;
  @Mock private TokenProvider tokenProvider;
  @Mock private TenantServiceClient tenantServiceClient;

  private JwtProperties jwtProperties;
  private OAuthImBindApplicationService service;

  @BeforeEach
  void setUp() {
    jwtProperties = new JwtProperties();
    jwtProperties.setExpirationMs(60000L);
    service = new OAuthImBindApplicationService(self, imOAuthClient, accountRepository,
        loginIdentityRepository, oauthLinkRepository, profileSyncRecordRepository, tokenProvider,
        jwtProperties, tenantServiceClient);
  }

  @Test
  void bind_issuesJwtForExistingLink() {
    when(imOAuthClient.fetchUserInfo("im-token"))
        .thenReturn(new ImOAuthUserInfo("open-1", "nick", "avatar", "phone", "scope"));
    when(oauthLinkRepository.findAllByProviderAccount("IM", "legacy", "open-1"))
        .thenReturn(List.of(OauthLink.bind(9L, "IM", "open-1", "scope")));
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
    when(oauthLinkRepository.findAllByProviderAccount("IM", "legacy", "open-2")).thenReturn(List.of());
    when(self.createAccountAndBind(eq("open-2"), any(ImOAuthUserInfo.class), eq("legacy"))).thenReturn(11L);
    when(tokenProvider.createAccessToken(11L, "open-2", 0)).thenReturn("signed-jwt-2");

    BindResult result = service.bind("im-token");

    assertEquals(11L, result.accountId());
    assertEquals("signed-jwt-2", result.accessToken());
    assertEquals(60L, result.expiresInSeconds());
    assertTrue(result.bound());
  }
}
