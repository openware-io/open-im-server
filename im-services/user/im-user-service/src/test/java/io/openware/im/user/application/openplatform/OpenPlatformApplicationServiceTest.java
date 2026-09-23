package io.openware.im.user.application.openplatform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.im.user.application.openplatform.command.ConsentQuery;
import io.openware.im.user.application.openplatform.result.ConsentView;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.openplatform.model.OauthAccessToken;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.openplatform.model.OpenApplication;
import io.openware.im.user.domain.openplatform.model.OpenApplicationStatus;
import io.openware.im.user.domain.openplatform.port.AppSecretHasher;
import io.openware.im.user.domain.openplatform.port.AuthorizationCodeStore;
import io.openware.im.user.domain.openplatform.port.AuthorizationRequestStore;
import io.openware.im.user.domain.openplatform.port.OauthTokenStore;
import io.openware.im.user.domain.openplatform.repository.OpenApplicationRepository;
import io.openware.im.user.domain.openplatform.repository.OpenUserAuthorizationRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 授权同意页准备：返回应用与范围视图，并校验回调地址/范围/登录态。 */
class OpenPlatformApplicationServiceTest {

  private final OpenApplicationRepository openApplicationRepository = mock(OpenApplicationRepository.class);
  private final OpenUserAuthorizationRepository openUserAuthorizationRepository = mock(OpenUserAuthorizationRepository.class);
  private final AppSecretHasher appSecretHasher = mock(AppSecretHasher.class);
  private final AuthorizationCodeStore authorizationCodeStore = mock(AuthorizationCodeStore.class);
  private final AuthorizationRequestStore authorizationRequestStore = mock(AuthorizationRequestStore.class);
  private final OauthTokenStore oauthTokenStore = mock(OauthTokenStore.class);
  private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
  private final OpenPlatformApplicationService service = new OpenPlatformApplicationService(
      openApplicationRepository, openUserAuthorizationRepository, appSecretHasher, authorizationCodeStore,
      authorizationRequestStore, oauthTokenStore, userAccountRepository);

  @BeforeEach
  void setUp() {
    OpenApplication app = new OpenApplication();
    app.restore(10L, "app-1", "测试应用", "测试主体", "THIRD_PARTY", "gvchat://oauth/callback", "hash",
        OpenApplicationStatus.APPROVED, List.of("profile.basic", "profile.phone"), null, 0L, null, 0L, null, 0L, null);
    when(openApplicationRepository.findByAppId("app-1")).thenReturn(Optional.of(app));
    when(openUserAuthorizationRepository.findByApplicationAndUser(10L, 1L)).thenReturn(Optional.empty());
  }

  @Test
  void prepareConsent_returnsAppAndScopes() {
    ConsentView view = service.prepareConsent(query("app-1", "profile.basic"));

    assertEquals("测试应用", view.appName());
    assertEquals(List.of("profile.basic", "profile.phone"), view.allScopes());
    assertEquals(List.of("profile.basic"), view.requestedScopes());
    assertTrue(view.existingAuthorizedScopes().isEmpty());
  }

  @Test
  void prepareConsent_defaultsToAllScopesWhenNotSpecified() {
    ConsentView view = service.prepareConsent(query("app-1", null));

    assertEquals(List.of("profile.basic", "profile.phone"), view.requestedScopes());
  }

  @Test
  void prepareConsent_rejectsUnregisteredRedirectUri() {
    ConsentQuery query = new ConsentQuery("app-1", "https://evil.example/cb", "profile.basic", null,
        "challenge", "S256", 1L);

    assertThrows(ApiException.class, () -> service.prepareConsent(query));
  }

  @Test
  void prepareConsent_acceptsExactUriRegisteredForCurrentEnvironment() {
    String lanCallback = "http://192.168.31.91:30082/a380/";
    when(openApplicationRepository.isRedirectUriRegistered("app-1", lanCallback)).thenReturn(true);

    ConsentView view = service.prepareConsent(new ConsentQuery("app-1", lanCallback, "profile.basic", "st",
        "challenge", "S256", 1L));

    assertEquals(lanCallback, view.redirectUri());
  }

  @Test
  void prepareConsent_rejectsScopeNotRegistered() {
    ConsentQuery query = new ConsentQuery("app-1", "gvchat://oauth/callback", "profile.admin", null,
        "challenge", "S256", 1L);

    assertThrows(ApiException.class, () -> service.prepareConsent(query));
  }

  @Test
  void prepareConsent_requiresAuthentication() {
    ConsentQuery query = new ConsentQuery("app-1", "gvchat://oauth/callback", "profile.basic", null,
        "challenge", "S256", null);

    assertThrows(ApiException.class, () -> service.prepareConsent(query));
  }

  @Test
  void userInfo_filtersFieldsByApprovedScope() {
    UserAccount account = UserAccount.register("user", "hash", "昵称", "", "13800138000", LocalDateTime.now());
    when(oauthTokenStore.findAccessToken("token")).thenReturn(Optional.of(
        new OauthAccessToken("im_1", "app-1", 1L, "profile.basic", System.currentTimeMillis() + 60_000, null)));
    when(userAccountRepository.findById(1L)).thenReturn(Optional.of(account));

    var result = service.getUserInfo("token");

    assertEquals("昵称", result.nickname());
    assertEquals("", result.phone());
  }

  private static ConsentQuery query(String appId, String scope) {
    return new ConsentQuery(appId, "gvchat://oauth/callback", scope, "st", "challenge", "S256", 1L);
  }
}
