package com.gvchat.im.user.application.openplatform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.dto.PageResult;
import com.gvchat.common.exception.ApiException;
import com.gvchat.im.user.api.controller.OidcMetadataController;
import com.gvchat.im.user.api.dto.response.OauthTokenResponse;
import com.gvchat.im.user.api.dto.response.OauthUserInfoResponse;
import com.gvchat.im.user.application.openplatform.command.AuthorizeCommand;
import com.gvchat.im.user.application.openplatform.command.TokenCommand;
import com.gvchat.im.user.application.openplatform.result.AuthorizeResult;
import com.gvchat.im.user.application.openplatform.result.OauthTokenResult;
import com.gvchat.im.user.application.openplatform.result.UserInfoResult;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.openplatform.PkceUtil;
import com.gvchat.im.user.domain.openplatform.model.AuthorizationCode;
import com.gvchat.im.user.domain.openplatform.model.AuthorizationRequest;
import com.gvchat.im.user.domain.openplatform.model.OauthAccessToken;
import com.gvchat.im.user.domain.openplatform.model.OauthRefreshToken;
import com.gvchat.im.user.domain.openplatform.model.OpenApplication;
import com.gvchat.im.user.domain.openplatform.model.OpenApplicationStatus;
import com.gvchat.im.user.domain.openplatform.model.OpenUserAuthorization;
import com.gvchat.im.user.domain.openplatform.port.AuthorizationCodeStore;
import com.gvchat.im.user.domain.openplatform.port.AuthorizationRequestStore;
import com.gvchat.im.user.domain.openplatform.port.OauthTokenStore;
import com.gvchat.im.user.domain.openplatform.repository.OpenApplicationRepository;
import com.gvchat.im.user.domain.openplatform.repository.OpenUserAuthorizationRepository;
import com.gvchat.im.user.handler.GlobalExceptionHandler;
import com.gvchat.im.user.infra.security.OidcTokenSigner;
import com.gvchat.im.user.infra.security.Sha256AppSecretHasher;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * OIDC Provider 授权码 + PKCE(S256) 全流程契约测试：
 * authorize→(consent APPROVE)→code→token、一次性 code、PKCE 校验、redirect_uri/code 归属、
 * confidential Basic vs PUBLIC(PKCE-only) 认证矩阵、refresh 轮换与重放、revoke/introspect、
 * userinfo pairwise sub 与 scope 过滤、ID Token JWS 验签、state/nonce 透传。
 *
 * <p>不依赖外部 Redis/MySQL：仓库与令牌/授权码存储均用内存假实现，风格对齐本仓其它服务单测，
 * 仅替换持久化端口，被测编排逻辑（OpenPlatformApplicationService）与签名器（OidcTokenSigner）走真实实现。
 */
class OidcTokenFlowTest {
  private static final long USER_ID = 7L;
  /** 同应用第二用户：用于验证重放撤销不再整应用生效。 */
  private static final long USER_ID_2 = 8L;
  private static final String APP_CONF = "app-conf";
  private static final String APP_PUB = "app-pub";
  private static final String APP_OTHER = "app-other";
  private static final String CALLBACK_CONF = "https://rp.example/cb";
  private static final String CALLBACK_PUB = "gvchat://pubapp/cb";
  private static final String CALLBACK_OTHER = "https://other.example/cb";
  private static final String CONF_SECRET = "conf-secret-value";
  private static final String OTHER_SECRET = "other-secret-value";
  /** 固定且合法的 code_verifier（43~128 ASCII），各用例共用。 */
  private static final String VERIFIER =
      "code-verifier-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String WRONG_VERIFIER = "definitely-the-wrong-code-verifier-0123456789";
  private static final String ISSUER = "http://issuer.test";
  private static final String OPENID_SCOPE = "openid profile.basic";
  private static final String NO_OPENID_SCOPE = "profile.basic";

  private final OidcTokenSigner signer = new OidcTokenSigner(ISSUER);
  private final Sha256AppSecretHasher secretHasher = new Sha256AppSecretHasher();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private InMemoryApplicationRepository applicationRepository;
  private InMemoryAuthorizationRepository authorizationRepository;
  private InMemoryAuthorizationCodeStore codeStore;
  private InMemoryAuthorizationRequestStore requestStore;
  private InMemoryOauthTokenStore tokenStore;
  private InMemoryUserAccountRepository userAccountRepository;
  private OpenPlatformApplicationService service;

  @BeforeEach
  void setUp() {
    applicationRepository = new InMemoryApplicationRepository();
    applicationRepository.put(app(100L, APP_CONF, "THIRD_PARTY", CALLBACK_CONF, secretHasher.hash(CONF_SECRET),
        List.of("openid", "profile.basic", "profile.phone")));
    applicationRepository.put(app(200L, APP_PUB, "PUBLIC", CALLBACK_PUB, null,
        List.of("openid", "profile.basic")));
    applicationRepository.put(app(300L, APP_OTHER, "THIRD_PARTY", CALLBACK_OTHER, secretHasher.hash(OTHER_SECRET),
        List.of("openid", "profile.basic")));
    authorizationRepository = new InMemoryAuthorizationRepository();
    codeStore = new InMemoryAuthorizationCodeStore();
    requestStore = new InMemoryAuthorizationRequestStore();
    tokenStore = new InMemoryOauthTokenStore();
    userAccountRepository = new InMemoryUserAccountRepository();
    userAccountRepository.put(USER_ID, UserAccount.register("user-" + USER_ID, "hash", "昵称七", "",
        "13800138000", LocalDateTime.now()));
    userAccountRepository.put(USER_ID_2, UserAccount.register("user-" + USER_ID_2, "hash", "昵称八", "",
        "13800138001", LocalDateTime.now()));
    service = new OpenPlatformApplicationService(applicationRepository, authorizationRepository, secretHasher,
        codeStore, requestStore, tokenStore, userAccountRepository, signer);
  }

  @Test
  void tokenFlow_exchangesCodeWithAccessRefreshExpiryAndScope() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "state-1", "nonce-1");

    assertNotNull(auth.authCode());
    assertFalse(auth.authCode().isBlank());
    assertEquals("state-1", auth.state());
    assertEquals(CALLBACK_CONF, auth.redirectUri());

    OauthTokenResult token = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);

    assertEquals("im_" + USER_ID, token.openId());
    assertEquals(7200L, token.expiresIn());
    assertEquals(OPENID_SCOPE, token.scope());
    assertNotNull(token.accessToken());
    assertNotNull(token.refreshToken());
    assertNotNull(token.idToken());

    // introspect active：能解析出 appId/open_id/scope/exp。
    Optional<OauthAccessToken> active = service.findActiveAccessToken(token.accessToken());
    assertTrue(active.isPresent());
    assertEquals(APP_CONF, active.get().appId());
    assertEquals("im_" + USER_ID, active.get().openId());
    assertEquals(OPENID_SCOPE, active.get().scope());
    assertTrue(active.get().expiresAt() > System.currentTimeMillis());
  }

  @Test
  void tokenFlow_idTokenGatedOnOpenidScope() {
    AuthorizeResult withOpenId = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    OauthTokenResult tokenWithOpenId =
        exchangeCode(APP_CONF, CONF_SECRET, withOpenId.authCode(), CALLBACK_CONF, VERIFIER);
    assertNotNull(tokenWithOpenId.idToken(), "scope 含 openid 时必须签发 id_token");

    AuthorizeResult withoutOpenId = authorizeAndApprove(APP_CONF, CALLBACK_CONF, NO_OPENID_SCOPE, "st2", "nc2");
    OauthTokenResult tokenWithoutOpenId =
        exchangeCode(APP_CONF, CONF_SECRET, withoutOpenId.authCode(), CALLBACK_CONF, VERIFIER);
    assertNull(tokenWithoutOpenId.idToken(), "scope 不含 openid 时不得签发 id_token");
    assertNotNull(tokenWithoutOpenId.accessToken());
    assertNotNull(tokenWithoutOpenId.refreshToken());
  }

  @Test
  void tokenResponse_serializesBearerTokenTypeAndFields() throws Exception {
    OauthTokenResponse response =
        new OauthTokenResponse("access-1", "refresh-1", 7200L, "im_7", OPENID_SCOPE, "jwt.payload");

    String json = objectMapper.writeValueAsString(response);

    assertTrue(json.contains("\"token_type\":\"Bearer\""), "token 成功响应必须含 token_type=Bearer");
    assertTrue(json.contains("\"access_token\":\"access-1\""));
    assertTrue(json.contains("\"refresh_token\":\"refresh-1\""));
    assertTrue(json.contains("\"expires_in\":7200"));
    assertTrue(json.contains("\"scope\":\"" + OPENID_SCOPE + "\""));
    assertTrue(json.contains("\"id_token\":\"jwt.payload\""));
    // 旧五参/六参构造兼容：默认仍带 token_type=Bearer。
    assertEquals("Bearer", new OauthTokenResponse("a", "r", 1L, "o", "s").tokenType());
    assertEquals("Bearer", new OauthTokenResponse("a", "r", 1L, "o", "s", null).tokenType());
  }

  @Test
  void authorizationCode_isSingleUse() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    assertNotNull(exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER).accessToken());

    ApiException replay = assertOauthError("invalid_grant",
        () -> exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER));
    assertEquals(400, replay.getStatus());
    assertOauthHttpError(replay, "invalid_grant");
  }

  @Test
  void tokenExchange_rejectsWrongPkceVerifierAsInvalidGrant() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");

    ApiException ex = assertOauthError("invalid_grant",
        () -> exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, WRONG_VERIFIER));
    assertOauthHttpError(ex, "invalid_grant");
  }

  @Test
  void authorize_rejectsNonS256CodeChallengeMethod() {
    ApiException ex = assertOauthError("invalid_request", () -> service.createAuthorizationRequest(
        new AuthorizeCommand(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "challenge-not-s256", "plain",
            USER_ID, "nc")));
    assertEquals(400, ex.getStatus());
  }

  @Test
  void authorize_rejectsRedirectUriNotRegistered() {
    ApiException ex = assertThrows(ApiException.class, () -> service.createAuthorizationRequest(
        new AuthorizeCommand(APP_CONF, "https://evil.example/cb", OPENID_SCOPE, "st",
            PkceUtil.computeChallenge(VERIFIER), "S256", USER_ID, "nc")));
    assertEquals(400, ex.getStatus());
  }

  @Test
  void tokenExchange_rejectsRedirectUriMismatchAsInvalidGrant() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");

    ApiException ex = assertOauthError("invalid_grant",
        () -> exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), "https://evil.example/cb", VERIFIER));
    assertOauthHttpError(ex, "invalid_grant");
  }

  @Test
  void tokenExchange_rejectsCodeIssuedToAnotherClient() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");

    // 用另一 confidential 应用的身份换取 app-conf 签发的 code。
    ApiException ex = assertOauthError("invalid_grant",
        () -> exchangeCode(APP_OTHER, OTHER_SECRET, auth.authCode(), CALLBACK_OTHER, VERIFIER));
    assertOauthHttpError(ex, "invalid_grant");
  }

  @Test
  void clientAuth_confidentialRequiresValidSecret() {
    AuthorizeResult code = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");

    // 错误 secret → 401 invalid_client（Basic 认证失败在消费 code 之前）。
    ApiException wrongSecret = assertThrows(ApiException.class,
        () -> exchangeCode(APP_CONF, "forged-secret", code.authCode(), CALLBACK_CONF, VERIFIER));
    assertEquals(401, wrongSecret.getStatus());
    assertOauthHttpError(wrongSecret, "invalid_client");

    // 无 secret → 同样 401 invalid_client。
    ApiException noSecret = assertThrows(ApiException.class,
        () -> exchangeCode(APP_CONF, null, code.authCode(), CALLBACK_CONF, VERIFIER));
    assertEquals(401, noSecret.getStatus());

    // 正确 secret（confidential HTTP Basic 语义）→ 通过。
    OauthTokenResult token = exchangeCode(APP_CONF, CONF_SECRET, code.authCode(), CALLBACK_CONF, VERIFIER);
    assertNotNull(token.accessToken());
  }

  @Test
  void clientAuth_publicClientIsPkceOnly() {
    // PUBLIC 无 secret（token_endpoint_auth_method=none）经 PKCE 成功。
    AuthorizeResult noSecret = authorizeAndApprove(APP_PUB, CALLBACK_PUB, OPENID_SCOPE, "st", "nc");
    OauthTokenResult token = exchangeCode(APP_PUB, null, noSecret.authCode(), CALLBACK_PUB, VERIFIER);
    assertNotNull(token.accessToken());
    assertNotNull(token.idToken());

    // 实现语义：PUBLIC 应用跳过 secret 比对（PKCE-only），携带伪造 secret 无法充当客户端认证——
    // 即使带着伪造 secret，PKCE verifier 错误仍以 invalid_grant 拒绝。
    AuthorizeResult forgedSecret = authorizeAndApprove(APP_PUB, CALLBACK_PUB, OPENID_SCOPE, "st2", "nc2");
    ApiException ex = assertOauthError("invalid_grant",
        () -> exchangeCode(APP_PUB, "forged-secret", forgedSecret.authCode(), CALLBACK_PUB, WRONG_VERIFIER));
    assertOauthHttpError(ex, "invalid_grant");
  }

  @Test
  void refreshToken_rotationIssuesNewPairAndNewRefreshStillWorks() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    OauthTokenResult first = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);

    OauthTokenResult rotated = exchangeRefresh(APP_CONF, CONF_SECRET, first.refreshToken());

    assertNotNull(rotated.accessToken());
    assertNotNull(rotated.refreshToken());
    assertNotEquals(first.refreshToken(), rotated.refreshToken());
    assertEquals("im_" + USER_ID, rotated.openId());

    // 新 refresh_token 可继续轮换使用。
    OauthTokenResult again = exchangeRefresh(APP_CONF, CONF_SECRET, rotated.refreshToken());
    assertNotNull(again.accessToken());
    assertNotNull(again.refreshToken());
    assertNotEquals(rotated.refreshToken(), again.refreshToken());
  }

  @Test
  void refreshToken_replayOfRotatedRefreshFailsWithInvalidGrant() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    OauthTokenResult first = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);
    OauthTokenResult rotated = exchangeRefresh(APP_CONF, CONF_SECRET, first.refreshToken());
    assertNotNull(rotated.refreshToken());

    ApiException replay = assertOauthError("invalid_grant",
        () -> exchangeRefresh(APP_CONF, CONF_SECRET, first.refreshToken()));
    assertOauthHttpError(replay, "invalid_grant");
  }

  @Test
  void refreshToken_replayRevokesOnlyThatFamily_sameUserOtherFamilyStaysActive() {
    // 用户第一条授权链（family 1）：轮换一次产生已用旧 refresh + 活跃派生 token。
    AuthorizeResult auth1 = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc", USER_ID);
    OauthTokenResult first = exchangeCode(APP_CONF, CONF_SECRET, auth1.authCode(), CALLBACK_CONF, VERIFIER);
    OauthTokenResult rotated = exchangeRefresh(APP_CONF, CONF_SECRET, first.refreshToken());
    // 同一用户在同一应用的新授权链（family 2），与 family 1 无关。
    AuthorizeResult auth2 = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st2", "nc2", USER_ID);
    OauthTokenResult second = exchangeCode(APP_CONF, CONF_SECRET, auth2.authCode(), CALLBACK_CONF, VERIFIER);
    assertTrue(service.findActiveAccessToken(second.accessToken()).isPresent());

    ApiException replay = assertOauthError("invalid_grant",
        () -> exchangeRefresh(APP_CONF, CONF_SECRET, first.refreshToken()));
    assertOauthHttpError(replay, "invalid_grant");

    // 同一应用、同一用户、另一授权链（另一 family）的 access_token 仍 active。
    assertTrue(service.findActiveAccessToken(second.accessToken()).isPresent(),
        "重放撤销只限被重放 family，同应用其它授权链的 access_token 必须仍 active");
    // 另一 family 的 refresh_token 仍可继续轮换。
    assertNotNull(exchangeRefresh(APP_CONF, CONF_SECRET, second.refreshToken()).accessToken(),
        "另一 family 的 refresh_token 不受重放影响");
    // 被重放 family 内轮换派生的活跃 access_token 一并失效。
    assertTrue(service.findActiveAccessToken(rotated.accessToken()).isEmpty(),
        "被重放 family 派生的活跃 access_token 应被族级撤销");
    assertTrue(service.findActiveAccessToken(first.accessToken()).isEmpty(),
        "首次签发同属该 family 的 access_token 应一并失效");
  }

  @Test
  void refreshToken_replayDoesNotRevokeWholeApplication_otherUserTokenStaysActive() {
    AuthorizeResult authA = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc", USER_ID);
    OauthTokenResult userA = exchangeCode(APP_CONF, CONF_SECRET, authA.authCode(), CALLBACK_CONF, VERIFIER);
    OauthTokenResult rotatedA = exchangeRefresh(APP_CONF, CONF_SECRET, userA.refreshToken());
    // 同应用另一用户独立授权链。
    AuthorizeResult authB = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc", USER_ID_2);
    OauthTokenResult userB = exchangeCode(APP_CONF, CONF_SECRET, authB.authCode(), CALLBACK_CONF, VERIFIER);
    assertTrue(service.findActiveAccessToken(userB.accessToken()).isPresent());

    ApiException replay = assertOauthError("invalid_grant",
        () -> exchangeRefresh(APP_CONF, CONF_SECRET, userA.refreshToken()));
    assertOauthHttpError(replay, "invalid_grant");

    // 重放撤销不触发整应用撤销：另一用户的 access_token 仍可 introspection（active）且 userinfo 可用。
    assertTrue(service.findActiveAccessToken(userB.accessToken()).isPresent(),
        "重放不得触发整应用 token 撤销，另一用户 access_token 必须仍 active");
    assertEquals("im_" + USER_ID_2, service.getUserInfo(userB.accessToken()).openId(),
        "另一用户 userinfo 仍可用，应用级授权未被撤销");
    assertTrue(service.findActiveAccessToken(rotatedA.accessToken()).isEmpty(),
        "被重放用户 family 内派生 token 应失效");
  }

  @Test
  void revoke_makesUserinfoAndIntrospectionFail() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    OauthTokenResult token = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);
    assertNotNull(service.getUserInfo(token.accessToken()).openId());
    assertTrue(service.findActiveAccessToken(token.accessToken()).isPresent());

    service.revokeToken(token.accessToken());

    // revoke 后 userinfo 401、introspect inactive。
    ApiException ex = assertThrows(ApiException.class, () -> service.getUserInfo(token.accessToken()));
    assertEquals(401, ex.getStatus());
    assertTrue(service.findActiveAccessToken(token.accessToken()).isEmpty());
  }

  @Test
  void introspect_reportsExpiredTokenInactive() {
    tokenStore.saveAccessToken("expired-token", new OauthAccessToken("im_" + USER_ID, APP_CONF, USER_ID,
        OPENID_SCOPE, System.currentTimeMillis() - 1000, null));

    assertTrue(service.findActiveAccessToken("expired-token").isEmpty());
    assertTrue(service.findActiveAccessToken("never-issued").isEmpty());
  }

  @Test
  void userinfo_returnsPairwiseSubConsistentWithIdToken() {
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    OauthTokenResult token = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);

    UserInfoResult info = service.getUserInfo(token.accessToken());

    String expected = pairwiseSubject(APP_CONF, USER_ID);
    assertEquals(expected, info.sub(), "userinfo sub 必须是与 ID Token 一致的 pairwise subject");
    assertEquals(expected, parseIdToken(token.idToken()).getSubject());
    assertEquals("im_" + USER_ID, info.openId(), "open_id 兼容字段保留");
    assertEquals(OPENID_SCOPE, info.scope());
  }

  @Test
  void userinfo_pairwiseSubDiffersAcrossApplications() {
    AuthorizeResult authConf = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, "st", "nc");
    OauthTokenResult tokenConf = exchangeCode(APP_CONF, CONF_SECRET, authConf.authCode(), CALLBACK_CONF, VERIFIER);
    AuthorizeResult authPub = authorizeAndApprove(APP_PUB, CALLBACK_PUB, OPENID_SCOPE, "st", "nc");
    OauthTokenResult tokenPub = exchangeCode(APP_PUB, null, authPub.authCode(), CALLBACK_PUB, VERIFIER);

    String subConf = service.getUserInfo(tokenConf.accessToken()).sub();
    String subPub = service.getUserInfo(tokenPub.accessToken()).sub();

    assertEquals(pairwiseSubject(APP_CONF, USER_ID), subConf);
    assertEquals(pairwiseSubject(APP_PUB, USER_ID), subPub);
    assertNotEquals(subConf, subPub, "不同 appId 的 pairwise sub 必须不同，避免跨应用关联同一用户");
    assertEquals(subConf, parseIdToken(tokenConf.idToken()).getSubject());
    assertEquals(subPub, parseIdToken(tokenPub.idToken()).getSubject());
  }

  @Test
  void userinfo_appliesScopeFiltering() {
    // 未授权 profile.phone：不返回 phone。
    AuthorizeResult noPhone = authorizeAndApprove(APP_CONF, CALLBACK_CONF, NO_OPENID_SCOPE, "st", "nc");
    OauthTokenResult tokenNoPhone = exchangeCode(APP_CONF, CONF_SECRET, noPhone.authCode(), CALLBACK_CONF, VERIFIER);
    UserInfoResult infoNoPhone = service.getUserInfo(tokenNoPhone.accessToken());
    assertEquals("昵称七", infoNoPhone.nickname());
    assertEquals("", infoNoPhone.phone(), "profile.phone 未授权时不得返回手机号");

    // 授权 profile.phone：返回脱敏手机号。
    AuthorizeResult withPhone = authorizeAndApprove(APP_CONF, CALLBACK_CONF, "openid profile.basic profile.phone",
        "st", "nc");
    OauthTokenResult tokenWithPhone =
        exchangeCode(APP_CONF, CONF_SECRET, withPhone.authCode(), CALLBACK_CONF, VERIFIER);
    UserInfoResult infoWithPhone = service.getUserInfo(tokenWithPhone.accessToken());
    assertEquals("昵称七", infoWithPhone.nickname());
    assertEquals("138****8000", infoWithPhone.phone());
  }

  @Test
  void userinfoResponse_serializesPairwiseSubAlongsideOpenId() throws Exception {
    String pairwise = pairwiseSubject(APP_CONF, USER_ID);
    OauthUserInfoResponse response =
        new OauthUserInfoResponse("im_" + USER_ID, "昵称七", "", "", NO_OPENID_SCOPE, pairwise);

    String json = objectMapper.writeValueAsString(response);

    assertTrue(json.contains("\"open_id\":\"im_" + USER_ID + "\""));
    assertTrue(json.contains("\"sub\":\"" + pairwise + "\""));
  }

  @Test
  void idToken_verifiesAsRs256WithJwksKidAndOidcClaims() {
    String state = "state-xyz-123";
    String nonce = "nonce-abc-456";
    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, state, nonce);
    OauthTokenResult token = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);

    Claims claims = parseIdToken(token.idToken());

    assertEquals("RS256", jwtHeader(token.idToken()).getAlgorithm());
    assertEquals(ISSUER, claims.getIssuer());
    assertEquals(pairwiseSubject(APP_CONF, USER_ID), claims.getSubject());
    assertTrue(claims.getAudience().contains(APP_CONF), "aud 必须含 clientId");
    assertEquals(APP_CONF, claims.get("azp"));
    assertEquals(nonce, claims.get("nonce"));
    assertNotNull(claims.getIssuedAt());
    assertNotNull(claims.getExpiration());
    assertEquals(300_000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime(),
        "id_token 有效期 300s");
    assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());

    // kid 与 JWKS 一致，可验签（parseIdToken 已用公钥校验签名）。
    String kid = jwtHeader(token.idToken()).getKeyId();
    assertEquals(signer.keyId(), kid);
    assertEquals(signer.jwk().get("kid"), kid);
    Map<?, ?> publishedJwk = (Map<?, ?>) ((List<?>) new OidcMetadataController(signer).jwks().get("keys")).get(0);
    assertEquals(kid, publishedJwk.get("kid"));
  }

  @Test
  void stateAndNonce_preservedFromAuthorizeThroughToken() {
    String state = "st-roundtrip-1";
    String nonce = "nc-roundtrip-2";

    AuthorizeResult auth = authorizeAndApprove(APP_CONF, CALLBACK_CONF, OPENID_SCOPE, state, nonce);
    OauthTokenResult token = exchangeCode(APP_CONF, CONF_SECRET, auth.authCode(), CALLBACK_CONF, VERIFIER);

    assertEquals(state, auth.state(), "state 必须原样返回给回调");
    assertEquals(nonce, parseIdToken(token.idToken()).get("nonce"), "nonce 必须原样进入 id_token");
  }

  private AuthorizeResult authorizeAndApprove(String appId, String redirectUri, String scope,
      String state, String nonce) {
    return authorizeAndApprove(appId, redirectUri, scope, state, nonce, USER_ID);
  }

  private AuthorizeResult authorizeAndApprove(String appId, String redirectUri, String scope,
      String state, String nonce, long userId) {
    String challenge = PkceUtil.computeChallenge(VERIFIER);
    String requestId = service.createAuthorizationRequest(
        new AuthorizeCommand(appId, redirectUri, scope, state, challenge, "S256", userId, nonce));
    return service.approveAuthorization(requestId, splitScope(scope));
  }

  private OauthTokenResult exchangeCode(String appId, String secret, String code, String redirectUri,
      String verifier) {
    return service.exchangeToken(new TokenCommand("authorization_code", code, verifier, null, appId, secret,
        redirectUri));
  }

  private OauthTokenResult exchangeRefresh(String appId, String secret, String refreshToken) {
    return service.exchangeToken(new TokenCommand("refresh_token", null, null, refreshToken, appId, secret, null));
  }

  private static List<String> splitScope(String scope) {
    return Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isBlank()).toList();
  }

  private static ApiException assertOauthError(String expectedCode, org.junit.jupiter.api.function.Executable action) {
    ApiException ex = assertThrows(ApiException.class, action);
    assertEquals(expectedCode, ex.getCode());
    return ex;
  }

  /** 校验 /oauth/** 路径下 GlobalExceptionHandler 翻译出的 OAuth error 码。 */
  private static void assertOauthHttpError(ApiException ex, String expectedError) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/oauth/token");
    ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler().handleApi(ex, request);
    assertEquals(expectedError, response.getBody().get("error"));
  }

  private Claims parseIdToken(String idToken) {
    return Jwts.parser().verifyWith(signer.publicKey()).build().parseSignedClaims(idToken).getPayload();
  }

  private JwsHeader jwtHeader(String idToken) {
    return Jwts.parser().verifyWith(signer.publicKey()).build().parseSignedClaims(idToken).getHeader();
  }

  private static String pairwiseSubject(String appId, long userId) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((appId + ":" + userId).getBytes(StandardCharsets.UTF_8));
      return "sub_" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static OpenApplication app(long id, String appId, String appType, String callbackUrl,
      String secretHash, List<String> scopes) {
    OpenApplication application = new OpenApplication();
    application.restore(id, appId, appId, "测试主体", appType, callbackUrl, secretHash,
        OpenApplicationStatus.APPROVED, scopes, null, 0L, LocalDateTime.now(), 0L, LocalDateTime.now(), 0L,
        LocalDateTime.now());
    return application;
  }

  /** 应用仓库内存实现：按 appId 提供 APPROVED 应用。 */
  private static final class InMemoryApplicationRepository implements OpenApplicationRepository {
    private final Map<String, OpenApplication> applications = new HashMap<>();

    void put(OpenApplication application) {
      applications.put(application.getAppId(), application);
    }

    @Override
    public Optional<OpenApplication> findByAppId(String appId) {
      return Optional.ofNullable(applications.get(appId));
    }

    @Override
    public OpenApplication save(OpenApplication application) {
      applications.put(application.getAppId(), application);
      return application;
    }

    @Override
    public PageResult<OpenApplication> list(OpenApplicationStatus status, String appType, int page, int pageSize) {
      throw new UnsupportedOperationException("contract test does not page applications");
    }
  }

  /** 用户级授权仓库内存实现：按 (applicationId, userId) 保留最近一次 save 的授权。 */
  private static final class InMemoryAuthorizationRepository implements OpenUserAuthorizationRepository {
    private final Map<String, OpenUserAuthorization> authorizations = new HashMap<>();
    private long nextId = 1;

    private static String key(Long applicationId, Long userId) {
      return applicationId + ":" + userId;
    }

    @Override
    public Optional<OpenUserAuthorization> findByApplicationAndUser(Long applicationId, Long userId) {
      return Optional.ofNullable(authorizations.get(key(applicationId, userId)));
    }

    @Override
    public List<OpenUserAuthorization> findByApplicationId(Long applicationId) {
      return authorizations.values().stream().filter(a -> applicationId.equals(a.getApplicationId())).toList();
    }

    @Override
    public OpenUserAuthorization save(OpenUserAuthorization authorization) {
      if (authorization.getId() == null) {
        authorization.assignId(nextId++);
      }
      authorizations.put(key(authorization.getApplicationId(), authorization.getUserId()), authorization);
      return authorization;
    }
  }

  /** 授权码存储内存实现：consume 即 GETDEL 一次性原子消费。 */
  private static final class InMemoryAuthorizationCodeStore implements AuthorizationCodeStore {
    private final Map<String, AuthorizationCode> codes = new HashMap<>();

    @Override
    public void save(AuthorizationCode authorizationCode) {
      codes.put(authorizationCode.code(), authorizationCode);
    }

    @Override
    public Optional<AuthorizationCode> findByCode(String code) {
      return Optional.ofNullable(codes.get(code));
    }

    @Override
    public void remove(String code) {
      codes.remove(code);
    }

    @Override
    public Optional<AuthorizationCode> consume(String code) {
      return Optional.ofNullable(codes.remove(code));
    }
  }

  /** 授权事务票据存储内存实现。 */
  private static final class InMemoryAuthorizationRequestStore implements AuthorizationRequestStore {
    private final Map<String, AuthorizationRequest> requests = new HashMap<>();

    @Override
    public void save(AuthorizationRequest request) {
      requests.put(request.requestId(), request);
    }

    @Override
    public Optional<AuthorizationRequest> findByRequestId(String requestId) {
      return Optional.ofNullable(requests.get(requestId));
    }

    @Override
    public void remove(String requestId) {
      requests.remove(requestId);
    }
  }

  /**
   * OAuth 令牌存储内存实现：语义对齐 RedisOauthTokenStore —— 按 app 索引 + 按 family 索引，
   * remove refresh 即写已用标记（记录 familyId），重放按 family 撤销。
   */
  private static final class InMemoryOauthTokenStore implements OauthTokenStore {
    private final Map<String, OauthAccessToken> accessTokens = new HashMap<>();
    private final Map<String, OauthRefreshToken> refreshTokens = new HashMap<>();
    private final Map<String, Set<String>> accessTokensByFamily = new HashMap<>();
    private final Map<String, Set<String>> refreshTokensByFamily = new HashMap<>();
    /** 无 family 信息的已用标记（对应 Redis 旧格式取值 "1"）。 */
    private final Set<String> usedRefreshTokens = new HashSet<>();
    /** 已用 refresh_token → 所属 familyId。 */
    private final Map<String, String> usedRefreshTokenFamily = new HashMap<>();

    @Override
    public void saveAccessToken(String token, OauthAccessToken accessToken) {
      accessTokens.put(token, accessToken);
      if (accessToken.familyId() != null) {
        accessTokensByFamily.computeIfAbsent(accessToken.familyId(), k -> new HashSet<>()).add(token);
      }
    }

    @Override
    public Optional<OauthAccessToken> findAccessToken(String token) {
      return Optional.ofNullable(accessTokens.get(token));
    }

    @Override
    public void removeAccessToken(String token) {
      OauthAccessToken existing = accessTokens.remove(token);
      if (existing != null && existing.familyId() != null) {
        dropFamilyMember(accessTokensByFamily, existing.familyId(), token);
      }
    }

    @Override
    public void saveRefreshToken(String token, OauthRefreshToken refreshToken) {
      refreshTokens.put(token, refreshToken);
      if (refreshToken.familyId() != null) {
        refreshTokensByFamily.computeIfAbsent(refreshToken.familyId(), k -> new HashSet<>()).add(token);
      }
    }

    @Override
    public Optional<OauthRefreshToken> findRefreshToken(String token) {
      return Optional.ofNullable(refreshTokens.get(token));
    }

    @Override
    public void removeRefreshToken(String token) {
      OauthRefreshToken existing = refreshTokens.remove(token);
      if (existing != null && existing.familyId() != null) {
        dropFamilyMember(refreshTokensByFamily, existing.familyId(), token);
        usedRefreshTokenFamily.put(token, existing.familyId());
      } else if (token != null) {
        usedRefreshTokens.add(token);
      }
    }

    @Override
    public boolean wasRefreshTokenUsed(String token) {
      return token != null && (usedRefreshTokens.contains(token) || usedRefreshTokenFamily.containsKey(token));
    }

    @Override
    public Optional<String> usedRefreshTokenFamily(String token) {
      return Optional.ofNullable(token == null ? null : usedRefreshTokenFamily.get(token));
    }

    @Override
    public void revokeApplication(String appId) {
      accessTokens.entrySet().removeIf(entry -> appId.equals(entry.getValue().appId()));
      refreshTokens.entrySet().removeIf(entry -> appId.equals(entry.getValue().appId()));
      pruneFamilyIndexes();
    }

    @Override
    public void revokeApplicationUser(String appId, long userId) {
      accessTokens.entrySet()
          .removeIf(entry -> appId.equals(entry.getValue().appId()) && entry.getValue().userId() == userId);
      refreshTokens.entrySet()
          .removeIf(entry -> appId.equals(entry.getValue().appId()) && entry.getValue().userId() == userId);
      pruneFamilyIndexes();
    }

    @Override
    public void revokeFamily(String familyId) {
      if (familyId == null || familyId.isBlank()) {
        return;
      }
      Set<String> access = accessTokensByFamily.remove(familyId);
      if (access != null) {
        access.forEach(accessTokens::remove);
      }
      Set<String> refresh = refreshTokensByFamily.remove(familyId);
      if (refresh != null) {
        refresh.forEach(refreshTokens::remove);
      }
    }

    /** 清理 family 索引中已不存在的 token（对齐 Redis 应用/用户撤销时的对偶清理）。 */
    private void pruneFamilyIndexes() {
      accessTokensByFamily.values().forEach(family -> family.removeIf(token -> !accessTokens.containsKey(token)));
      accessTokensByFamily.entrySet().removeIf(entry -> entry.getValue().isEmpty());
      refreshTokensByFamily.values().forEach(family -> family.removeIf(token -> !refreshTokens.containsKey(token)));
      refreshTokensByFamily.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static void dropFamilyMember(Map<String, Set<String>> familyIndex, String familyId, String token) {
      Set<String> family = familyIndex.get(familyId);
      if (family != null) {
        family.remove(token);
        if (family.isEmpty()) {
          familyIndex.remove(familyId);
        }
      }
    }
  }

  /** 账号仓库内存实现：仅契约测试用到的 findById 有行为，其余抛不支持。 */
  private static final class InMemoryUserAccountRepository implements UserAccountRepository {
    private final Map<Long, UserAccount> users = new HashMap<>();

    void put(long id, UserAccount account) {
      users.put(id, account);
    }

    @Override
    public boolean existsByUsername(String username) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public boolean existsByEmail(String email) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public Optional<UserAccount> findById(Long id) {
      return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public Optional<UserAccount> findByPhone(String phone) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public List<UserAccount> search(String keyword, int limit) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public PageResult<UserAccount> searchForAdmin(String username, UserAccountStatus status, String keyword,
        Long numericUserId, int page, int pageSize) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public List<Long> findIdsMatchingKeyword(String keyword) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public long countAll() {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public long countCreatedSince(LocalDateTime since) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public List<Map<String, Object>> dailyCounts(LocalDateTime since) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public UserAccount save(UserAccount account) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public List<UserAccount> findSelfDestructDue(LocalDateTime now, int limit) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }

    @Override
    public int hardDelete(Long id) {
      throw new UnsupportedOperationException("not used in OIDC contract tests");
    }
  }
}
