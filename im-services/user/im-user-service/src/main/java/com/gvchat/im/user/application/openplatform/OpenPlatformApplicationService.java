package com.gvchat.im.user.application.openplatform;

import com.gvchat.common.dto.PageResult;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.user.application.openplatform.command.AuthorizeCommand;
import com.gvchat.im.user.application.openplatform.command.ConsentQuery;
import com.gvchat.im.user.application.openplatform.command.RegisterApplicationCommand;
import com.gvchat.im.user.application.openplatform.command.UpdateApplicationCommand;
import com.gvchat.im.user.application.openplatform.command.TokenCommand;
import com.gvchat.im.user.application.openplatform.result.ApplicationResult;
import com.gvchat.im.user.application.openplatform.result.AuthorizeResult;
import com.gvchat.im.user.application.openplatform.result.ConsentView;
import com.gvchat.im.user.application.openplatform.result.OauthTokenResult;
import com.gvchat.im.user.application.openplatform.result.RegisteredApplicationResult;
import com.gvchat.im.user.application.openplatform.result.UserInfoResult;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.openplatform.PkceUtil;
import com.gvchat.im.user.domain.openplatform.model.AuthorizationCode;
import com.gvchat.im.user.domain.openplatform.model.AuthorizationRequest;
import com.gvchat.im.user.domain.openplatform.model.OauthAccessToken;
import com.gvchat.im.user.domain.openplatform.model.OauthRefreshToken;
import com.gvchat.im.user.domain.openplatform.model.OpenApplication;
import com.gvchat.im.user.domain.openplatform.model.OpenApplicationStatus;
import com.gvchat.im.user.domain.openplatform.model.OpenUserAuthorization;
import com.gvchat.im.user.domain.openplatform.model.OpenUserAuthorizationStatus;
import com.gvchat.im.user.domain.openplatform.port.AppSecretHasher;
import com.gvchat.im.user.domain.openplatform.port.AuthorizationCodeStore;
import com.gvchat.im.user.domain.openplatform.port.AuthorizationRequestStore;
import com.gvchat.im.user.domain.openplatform.port.OauthTokenStore;
import com.gvchat.im.user.domain.openplatform.repository.OpenApplicationRepository;
import com.gvchat.im.user.domain.openplatform.repository.OpenUserAuthorizationRepository;
import com.gvchat.im.user.domain.openplatform.port.OidcTokenSigner;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开放平台（OAuth 2.0 Provider）应用编排服务：
 * 授权码 + PKCE(S256)，两层授权（应用级登记 + 用户级授权），access_token/refresh_token 走 Redis 不透明令牌。
 *
 * <p>脚手架边界：注册即 APPROVED（无人工审核）；/oauth/authorize 自动授权（无同意页）；
 * 撤销为全量撤销应用与其用户授权。真实审核、同意页与审计事件留待后续。
 */
@Service
@Slf4j
public class OpenPlatformApplicationService {
  private static final long ACCESS_TOKEN_TTL_SECONDS = 7200L;
  private static final long AUTHORIZATION_CODE_TTL_MILLIS = 300_000L;
  private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
  private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
  private static final Set<String> ALLOWED_SCOPES = Set.of("openid", "profile.basic", "profile.phone");
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final OpenApplicationRepository openApplicationRepository;
  private final OpenUserAuthorizationRepository openUserAuthorizationRepository;
  private final AppSecretHasher appSecretHasher;
  private final AuthorizationCodeStore authorizationCodeStore;
  private final AuthorizationRequestStore authorizationRequestStore;
  private final OauthTokenStore oauthTokenStore;
  private final UserAccountRepository userAccountRepository;
  private final OidcTokenSigner oidcTokenSigner;

  @Autowired
  public OpenPlatformApplicationService(OpenApplicationRepository openApplicationRepository,
      OpenUserAuthorizationRepository openUserAuthorizationRepository, AppSecretHasher appSecretHasher,
      AuthorizationCodeStore authorizationCodeStore, AuthorizationRequestStore authorizationRequestStore,
      OauthTokenStore oauthTokenStore, UserAccountRepository userAccountRepository, OidcTokenSigner oidcTokenSigner) {
    this.openApplicationRepository = openApplicationRepository;
    this.openUserAuthorizationRepository = openUserAuthorizationRepository;
    this.appSecretHasher = appSecretHasher;
    this.authorizationCodeStore = authorizationCodeStore;
    this.authorizationRequestStore = authorizationRequestStore;
    this.oauthTokenStore = oauthTokenStore;
    this.userAccountRepository = userAccountRepository;
    this.oidcTokenSigner = oidcTokenSigner;
  }

  public OpenPlatformApplicationService(OpenApplicationRepository openApplicationRepository,
      OpenUserAuthorizationRepository openUserAuthorizationRepository, AppSecretHasher appSecretHasher,
      AuthorizationCodeStore authorizationCodeStore, AuthorizationRequestStore authorizationRequestStore,
      OauthTokenStore oauthTokenStore, UserAccountRepository userAccountRepository) {
    this(openApplicationRepository, openUserAuthorizationRepository, appSecretHasher, authorizationCodeStore,
        authorizationRequestStore, oauthTokenStore, userAccountRepository, null);
  }

  @Transactional
  public RegisteredApplicationResult registerApplication(RegisterApplicationCommand command) {
    validateRegisterCommand(command);
    String appId = "app_" + randomHex(16);
    String appType = command.appType() == null || command.appType().isBlank() ? "THIRD_PARTY" : command.appType();
    String subjectName = command.subjectName() == null || command.subjectName().isBlank() ? command.appName() : command.subjectName();
    OpenApplication application = OpenApplication.register(appId, command.appName(), subjectName, appType,
        command.callbackUrl(), null, normalizeScopes(command.scopes()), LocalDateTime.now());
    openApplicationRepository.save(application);
    log.info("开放平台应用申请登记, appId={}, appName={}, subjectName={}, appType={}, status=PENDING", appId, command.appName(), subjectName, appType);
    return new RegisteredApplicationResult(appId, null, command.appName(), OpenApplicationStatus.PENDING.name());
  }

  @Transactional(readOnly = true)
  public ApplicationResult getApplication(String appId) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    return toResult(app);
  }

  @Transactional
  public ApplicationResult updateApplication(String appId, UpdateApplicationCommand command) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    String callbackUrl = command.callbackUrl();
    if (callbackUrl == null || callbackUrl.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "callbackUrl is required");
    }
    validateCallbackUrl(callbackUrl);
    List<String> scopes = normalizeScopes(command.scopes());
    if (scopes.isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "scopes must not be empty");
    }
    if (!ALLOWED_SCOPES.containsAll(scopes)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_scope", "Requested scope is not supported");
    }
    boolean scopesChanged = !app.getScopes().equals(scopes);
    app.updateInfo(callbackUrl, scopes, LocalDateTime.now());
    openApplicationRepository.save(app);
    if (scopesChanged) {
      // scope 变更后使既有用户级授权失效，要求受影响用户重新授权。
      LocalDateTime now = LocalDateTime.now();
      for (OpenUserAuthorization auth : openUserAuthorizationRepository.findByApplicationId(app.getId())) {
        if (auth.getStatus() == OpenUserAuthorizationStatus.ACTIVE) {
          auth.revoke(now);
          openUserAuthorizationRepository.save(auth);
        }
      }
      oauthTokenStore.revokeApplication(appId);
    }
    log.info("开放平台应用信息更新, appId={}, scopesChanged={}", appId, scopesChanged);
    return toResult(app);
  }

  @Transactional
  public AuthorizeResult authorize(AuthorizeCommand command) {
    if (!PkceUtil.isS256(command.codeChallengeMethod())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Unsupported code_challenge_method, only S256 is allowed");
    }
    if (command.codeChallenge() == null || command.codeChallenge().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "code_challenge is required");
    }
    if (command.userId() == null) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Authentication required");
    }
    ResolvedAuthorization resolved = resolveAuthorizationContext(command.appId(), command.redirectUri(), command.scope());
    String scope = String.join(" ", resolved.requestedScopes());
    String openId = deriveOpenId(command.userId());
    upsertAuthorization(resolved.application().getId(), command.userId(), openId, scope, LocalDateTime.now());
    String code = randomHex(32);
    authorizationCodeStore.save(new AuthorizationCode(code, command.appId(), command.userId(),
        command.redirectUri(), command.codeChallenge(), scope,
        System.currentTimeMillis() + AUTHORIZATION_CODE_TTL_MILLIS, command.nonce()));
    log.info("开放平台授权码签发, appId={}, userId={}, openId={}", command.appId(), command.userId(), openId);
    return new AuthorizeResult(command.redirectUri(), code, command.state());
  }

  /** 创建授权事务票据；票据替代 URL 中的登录 JWT，供同意页继续完成授权。 */
  @Transactional
  public String createAuthorizationRequest(AuthorizeCommand command) {
    validateAuthorizeCommand(command);
    ResolvedAuthorization resolved = resolveAuthorizationContext(command.appId(), command.redirectUri(), command.scope());
    String requestId = randomHex(32);
    authorizationRequestStore.save(new AuthorizationRequest(requestId, command.appId(), command.redirectUri(),
        String.join(" ", resolved.requestedScopes()), command.state(), command.codeChallenge(),
        command.codeChallengeMethod(), command.userId(), System.currentTimeMillis() + AUTHORIZATION_CODE_TTL_MILLIS,
        command.nonce()));
    return requestId;
  }

  @Transactional(readOnly = true)
  public ConsentView prepareConsent(String requestId) {
    AuthorizationRequest request = getAuthorizationRequest(requestId);
    ResolvedAuthorization resolved = resolveAuthorizationContext(request.appId(), request.redirectUri(), request.scope());
    List<String> existingScopes = existingAuthorizedScopes(resolved.application().getId(), request.userId());
    return new ConsentView(request.requestId(), resolved.application().getAppId(), resolved.application().getAppName(),
        request.redirectUri(), resolved.application().getScopes(), resolved.requestedScopes(), existingScopes,
        request.state(), request.codeChallenge(), request.codeChallengeMethod());
  }

  /** 同意页提交：只允许提交原授权事务中申请范围的子集，事务成功后立即消费。 */
  @Transactional
  public AuthorizeResult approveAuthorization(String requestId, List<String> selectedScopes) {
    AuthorizationRequest request = getAuthorizationRequest(requestId);
    List<String> requestedScopes = normalizeScopes(request.scope());
    List<String> approvedScopes = selectedScopes == null ? requestedScopes : normalizeScopes(selectedScopes);
    if (approvedScopes.isEmpty() || !requestedScopes.containsAll(approvedScopes)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "access_denied", "User denied requested scopes");
    }
    authorizationRequestStore.remove(requestId);
    return authorize(new AuthorizeCommand(request.appId(), request.redirectUri(), String.join(" ", approvedScopes),
        request.state(), request.codeChallenge(), request.codeChallengeMethod(), request.userId(), request.nonce()));
  }

  @Transactional
  public AuthorizationRequest rejectAuthorization(String requestId) {
    AuthorizationRequest request = getAuthorizationRequest(requestId);
    authorizationRequestStore.remove(requestId);
    return request;
  }

  @Transactional
  public void revokeUserAuthorization(String appId, long userId) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    openUserAuthorizationRepository.findByApplicationAndUser(app.getId(), userId).ifPresent(auth -> {
      auth.revoke(LocalDateTime.now());
      openUserAuthorizationRepository.save(auth);
    });
    oauthTokenStore.revokeApplicationUser(appId, userId);
    log.info("开放平台用户授权撤销, appId={}, userId={}", appId, userId);
  }

  /** RFC 7009 风格的 token 撤销；未知 token 也返回成功，避免泄露 token 是否存在。 */
  @Transactional
  public void revokeToken(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    oauthTokenStore.findAccessToken(token).ifPresent(value -> oauthTokenStore.removeAccessToken(token));
    oauthTokenStore.findRefreshToken(token).ifPresent(value -> oauthTokenStore.removeRefreshToken(token));
  }

  /** 准备授权同意页视图：校验 PKCE/应用/回调/范围，返回应用信息与可勾选范围（含已授权范围预选）。 */
  @Transactional(readOnly = true)
  public ConsentView prepareConsent(ConsentQuery query) {
    if (!PkceUtil.isS256(query.codeChallengeMethod())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Unsupported code_challenge_method, only S256 is allowed");
    }
    if (query.codeChallenge() == null || query.codeChallenge().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "code_challenge is required");
    }
    if (query.userId() == null) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Authentication required");
    }
    ResolvedAuthorization resolved = resolveAuthorizationContext(query.appId(), query.redirectUri(), query.scope());
    List<String> existingScopes = existingAuthorizedScopes(resolved.application().getId(), query.userId());
    return new ConsentView(null, resolved.application().getAppId(), resolved.application().getAppName(),
        query.redirectUri(), resolved.application().getScopes(), resolved.requestedScopes(), existingScopes,
        query.state(), query.codeChallenge(), query.codeChallengeMethod());
  }

  /** 校验 PKCE 之外的公共授权上下文：应用状态、回调地址、请求范围合法性。 */
  private ResolvedAuthorization resolveAuthorizationContext(String appId, String redirectUri, String scope) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    if (app.getStatus() != OpenApplicationStatus.APPROVED) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Application is not approved");
    }
    if (!app.getCallbackUrl().equals(redirectUri)
        && !openApplicationRepository.isRedirectUriRegistered(appId, redirectUri)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "redirect_uri is not registered");
    }
    List<String> requestedScopes = normalizeScopes(scope);
    if (requestedScopes.isEmpty()) {
      requestedScopes = app.getScopes();
    }
    if (!app.getScopes().containsAll(requestedScopes)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Requested scope is not registered");
    }
    if (!ALLOWED_SCOPES.containsAll(requestedScopes)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_scope", "Requested scope is not supported");
    }
    return new ResolvedAuthorization(app, requestedScopes);
  }

  private List<String> existingAuthorizedScopes(Long applicationId, Long userId) {
    return openUserAuthorizationRepository.findByApplicationAndUser(applicationId, userId)
        .filter(auth -> auth.getStatus() == OpenUserAuthorizationStatus.ACTIVE)
        .map(auth -> normalizeScopes(auth.getScope()))
        .orElse(List.of());
  }

  private record ResolvedAuthorization(OpenApplication application, List<String> requestedScopes) {
  }

  @Transactional
  public OauthTokenResult exchangeToken(TokenCommand command) {
    if (GRANT_TYPE_AUTHORIZATION_CODE.equals(command.grantType())) {
      return exchangeAuthorizationCode(command);
    }
    if (GRANT_TYPE_REFRESH_TOKEN.equals(command.grantType())) {
      return exchangeRefreshToken(command);
    }
    throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Unsupported grant_type");
  }

  private OauthTokenResult exchangeAuthorizationCode(TokenCommand command) {
    OpenApplication app = authenticateApplication(command.appId(), command.appSecret());
    AuthorizationCode authorizationCode = authorizationCodeStore.consume(command.code())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant",
            "Invalid or expired authorization code"));
    if (authorizationCode.expiresAt() < System.currentTimeMillis()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant",
          "Invalid or expired authorization code");
    }
    if (!authorizationCode.appId().equals(command.appId())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant",
          "Authorization code was not issued to this application");
    }
    if (!authorizationCode.redirectUri().equals(command.redirectUri())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant", "redirect_uri does not match");
    }
    if (!PkceUtil.verify(command.codeVerifier(), authorizationCode.codeChallenge())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant", "PKCE code_verifier is invalid");
    }
    OpenUserAuthorization authorization = openUserAuthorizationRepository
        .findByApplicationAndUser(app.getId(), authorizationCode.userId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.FORBIDDEN, "User authorization not found"));
    if (authorization.getStatus() != OpenUserAuthorizationStatus.ACTIVE) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "User authorization has been revoked");
    }
    String accessToken = randomHex(32);
    String refreshToken = randomHex(32);
    // 首次授权建立稳定家族 id，随后续 refresh 轮换继承，用于重放时族级撤销。
    String familyId = randomHex(16);
    long now = System.currentTimeMillis();
    oauthTokenStore.saveAccessToken(accessToken, new OauthAccessToken(authorization.getOpenId(), command.appId(),
        authorizationCode.userId(), authorizationCode.scope(), now + ACCESS_TOKEN_TTL_SECONDS * 1000L, familyId));
    oauthTokenStore.saveRefreshToken(refreshToken, new OauthRefreshToken(authorization.getOpenId(), command.appId(),
        authorizationCode.userId(), authorizationCode.scope(), familyId));
    log.info("开放平台 token 签发, appId={}, userId={}, openId={}", command.appId(), authorizationCode.userId(),
        authorization.getOpenId());
    // OIDC 门控：仅当授权 scope 含 openid 时才签发 id_token。
    String idToken = null;
    if (oidcTokenSigner != null && hasScope(authorizationCode.scope(), "openid")) {
      idToken = oidcTokenSigner.signIdToken(
          pairwiseSubject(command.appId(), authorizationCode.userId()), command.appId(), authorizationCode.nonce(),
          authorizationCode.scope(), authorizationCode.userId());
    }
    return new OauthTokenResult(accessToken, refreshToken, ACCESS_TOKEN_TTL_SECONDS,
        authorization.getOpenId(), authorizationCode.scope(), idToken);
  }

  private OauthTokenResult exchangeRefreshToken(TokenCommand command) {
    OpenApplication app = authenticateApplication(command.appId(), command.appSecret());
    if (command.refreshToken() == null || command.refreshToken().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant", "refresh_token is required");
    }
    OauthRefreshToken refreshToken = oauthTokenStore.findRefreshToken(command.refreshToken())
        .orElseGet(() -> {
          // 重放：旧 refresh 已被轮换消费。撤销仅限该 refresh 家族派生的 token，
          // 避免单次重放登出整个应用；旧格式已用标记无家族信息时仅拒绝、不升级为整应用撤销。
          oauthTokenStore.usedRefreshTokenFamily(command.refreshToken()).ifPresent(oauthTokenStore::revokeFamily);
          throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant",
              "Invalid or expired refresh_token");
        });
    if (!refreshToken.appId().equals(command.appId())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_grant",
          "refresh_token was not issued to this application");
    }
    OpenUserAuthorization authorization = openUserAuthorizationRepository
        .findByApplicationAndUser(app.getId(), refreshToken.userId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.FORBIDDEN, "User authorization not found"));
    if (authorization.getStatus() != OpenUserAuthorizationStatus.ACTIVE) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "User authorization has been revoked");
    }
    // 轮换：旧 refresh_token 立即作废，重新签发 access_token/refresh_token，家族 id 随链继承。
    oauthTokenStore.removeRefreshToken(command.refreshToken());
    String accessToken = randomHex(32);
    String newRefreshToken = randomHex(32);
    String familyId = refreshToken.familyId() == null ? randomHex(16) : refreshToken.familyId();
    long now = System.currentTimeMillis();
    oauthTokenStore.saveAccessToken(accessToken, new OauthAccessToken(refreshToken.openId(), command.appId(),
        refreshToken.userId(), refreshToken.scope(), now + ACCESS_TOKEN_TTL_SECONDS * 1000L, familyId));
    oauthTokenStore.saveRefreshToken(newRefreshToken, new OauthRefreshToken(refreshToken.openId(), command.appId(),
        refreshToken.userId(), refreshToken.scope(), familyId));
    log.info("开放平台 refresh_token 轮换, appId={}, userId={}, openId={}", command.appId(), refreshToken.userId(),
        refreshToken.openId());
    // OIDC 门控：仅当 scope 含 openid 时才签发 id_token（refresh 换发 nonce=null，符合预期）。
    String idToken = null;
    if (oidcTokenSigner != null && hasScope(refreshToken.scope(), "openid")) {
      idToken = oidcTokenSigner.signIdToken(
          pairwiseSubject(command.appId(), refreshToken.userId()), command.appId(), null,
          refreshToken.scope(), refreshToken.userId());
    }
    return new OauthTokenResult(accessToken, newRefreshToken, ACCESS_TOKEN_TTL_SECONDS,
        refreshToken.openId(), refreshToken.scope(), idToken);
  }

  private OpenApplication authenticateApplication(String appId, String appSecret) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid client credentials"));
    boolean publicClient = "PUBLIC".equalsIgnoreCase(app.getAppType())
        || "none".equalsIgnoreCase(app.getAppType());
    if (!publicClient && !appSecretHasher.matches(appSecret, app.getAppSecretHash())) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid client credentials");
    }
    if (app.getStatus() != OpenApplicationStatus.APPROVED) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Application is not approved");
    }
    return app;
  }

  @Transactional(readOnly = true)
  public UserInfoResult getUserInfo(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Missing access_token");
    }
    OauthAccessToken token = oauthTokenStore.findAccessToken(accessToken)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid access_token"));
    if (token.expiresAt() < System.currentTimeMillis()) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid access_token");
    }
    OpenApplication app = openApplicationRepository.findByAppId(token.appId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid access_token"));
    if (app.getStatus() != OpenApplicationStatus.APPROVED) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid access_token");
    }
    UserAccount account = userAccountRepository.findById(token.userId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
    String nickname = account.getNickname() == null || account.getNickname().isBlank()
        ? account.getUsername() : account.getNickname();
    String tokenScope = token.scope() == null ? "" : token.scope();
    String resolvedNickname = hasScope(tokenScope, "profile.basic") ? nickname : "";
    String resolvedAvatar = hasScope(tokenScope, "profile.basic") ? account.getAvatar() : "";
    String resolvedPhone = hasScope(tokenScope, "profile.phone") ? maskPhone(account.getPhone()) : "";
    // userinfo 的 sub 与 ID Token 保持一致：按 token.appId 派生的 pairwise subject，避免跨应用关联。
    String pairwiseSub = pairwiseSubject(token.appId(), token.userId());
    return new UserInfoResult(token.openId(), resolvedNickname, resolvedAvatar, resolvedPhone, tokenScope,
        pairwiseSub);
  }

  @Transactional
  public void revokeApplication(String appId) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    app.revoke(LocalDateTime.now());
    openApplicationRepository.save(app);
    LocalDateTime now = LocalDateTime.now();
    for (OpenUserAuthorization auth : openUserAuthorizationRepository.findByApplicationId(app.getId())) {
      auth.revoke(now);
      openUserAuthorizationRepository.save(auth);
    }
    oauthTokenStore.revokeApplication(appId);
    log.info("开放平台应用撤销, appId={}", appId);
  }

  /** 审核通过：仅 PENDING 应用可批准，分配 appSecret（仅此次返回明文，库内存哈希）。 */
  @Transactional
  public RegisteredApplicationResult approveApplication(String appId, Long reviewerId) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    if (app.getStatus() != OpenApplicationStatus.PENDING) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Only pending application can be approved");
    }
    String appSecret = randomHex(32);
    app.approve(appSecretHasher.hash(appSecret), reviewerId, LocalDateTime.now());
    openApplicationRepository.save(app);
    log.info("开放平台应用审核通过, appId={}", appId);
    return new RegisteredApplicationResult(app.getAppId(), appSecret, app.getAppName(), app.getStatus().name());
  }

  /** 审核驳回：仅 PENDING 应用可驳回，记录原因。 */
  @Transactional
  public ApplicationResult rejectApplication(String appId, String reason, Long reviewerId) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    if (app.getStatus() != OpenApplicationStatus.PENDING) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Only pending application can be rejected");
    }
    app.reject(reason, reviewerId, LocalDateTime.now());
    openApplicationRepository.save(app);
    log.info("开放平台应用审核驳回, appId={}, reason={}", appId, reason);
    return toResult(app);
  }

  /** 重置密钥：仅 APPROVED 应用可重置，新密钥仅此次返回明文。 */
  @Transactional
  public RegisteredApplicationResult resetSecret(String appId) {
    OpenApplication app = openApplicationRepository.findByAppId(appId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Application not found"));
    if (app.getStatus() != OpenApplicationStatus.APPROVED) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Only approved application can reset secret");
    }
    String appSecret = randomHex(32);
    app.resetSecret(appSecretHasher.hash(appSecret), LocalDateTime.now());
    openApplicationRepository.save(app);
    log.info("开放平台应用密钥重置, appId={}", appId);
    return new RegisteredApplicationResult(app.getAppId(), appSecret, app.getAppName(), app.getStatus().name());
  }

  /** 分页列应用（可按审核状态与接入类型过滤），供管理端审核。 */
  @Transactional(readOnly = true)
  public PageResult<ApplicationResult> listApplications(OpenApplicationStatus status, String appType, int page, int pageSize) {
    PageResult<OpenApplication> result = openApplicationRepository.list(status, appType, page, pageSize);
    return PageResult.<ApplicationResult>builder()
        .items(result.getItems().stream().map(this::toResult).toList())
        .total(result.getTotal()).page(page).pageSize(pageSize).build();
  }

  private ApplicationResult toResult(OpenApplication app) {
    return new ApplicationResult(app.getAppId(), app.getAppName(), app.getSubjectName(), app.getAppType(),
        app.getCallbackUrl(), app.getScopes(), app.getStatus().name(), app.getRejectReason(), app.getReviewedAt());
  }

  private void validateRegisterCommand(RegisterApplicationCommand command) {
    if (command.appName() == null || command.appName().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "appName is required");
    }
    if (command.callbackUrl() == null || command.callbackUrl().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "callbackUrl is required");
    }
    validateCallbackUrl(command.callbackUrl());
    if (command.scopes() == null || normalizeScopes(command.scopes()).isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "scopes must not be empty");
    }
    if (!ALLOWED_SCOPES.containsAll(normalizeScopes(command.scopes()))) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_scope", "Requested scope is not supported");
    }
  }

  private void upsertAuthorization(Long applicationId, Long userId, String openId, String scope,
      LocalDateTime occurredAt) {
    Optional<OpenUserAuthorization> existing = openUserAuthorizationRepository
        .findByApplicationAndUser(applicationId, userId);
    if (existing.isPresent()) {
      OpenUserAuthorization auth = existing.get();
      auth.reauthorize(openId, scope, occurredAt);
      openUserAuthorizationRepository.save(auth);
    } else {
      openUserAuthorizationRepository.save(OpenUserAuthorization.grant(applicationId, userId, openId, scope, occurredAt));
    }
  }

  private String deriveOpenId(Long userId) {
    return "im_" + userId;
  }

  @Transactional(readOnly = true)
  public Optional<OauthAccessToken> findActiveAccessToken(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      return Optional.empty();
    }
    return oauthTokenStore.findAccessToken(accessToken)
        .filter(token -> token.expiresAt() >= System.currentTimeMillis());
  }

  private String pairwiseSubject(String appId, long userId) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest((appId + ":" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "sub_" + java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private void validateAuthorizeCommand(AuthorizeCommand command) {
    if (!PkceUtil.isS256(command.codeChallengeMethod())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_request", "Only S256 code_challenge_method is allowed");
    }
    if (command.codeChallenge() == null || command.codeChallenge().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_request", "code_challenge is required");
    }
    if (command.userId() == null) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "unauthorized", "Authentication required");
    }
  }

  private AuthorizationRequest getAuthorizationRequest(String requestId) {
    AuthorizationRequest request = authorizationRequestStore.findByRequestId(requestId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_request", "Authorization request expired"));
    if (request.expiresAt() < System.currentTimeMillis()) {
      authorizationRequestStore.remove(requestId);
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_request", "Authorization request expired");
    }
    return request;
  }

  private void validateCallbackUrl(String callbackUrl) {
    if (callbackUrl.chars().anyMatch(Character::isWhitespace)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_request", "callbackUrl must not contain whitespace");
    }
    try {
      java.net.URI uri = java.net.URI.create(callbackUrl);
      if (uri.getFragment() != null || uri.getUserInfo() != null) {
        throw new IllegalArgumentException();
      }
      String scheme = uri.getScheme();
      if ("https".equalsIgnoreCase(scheme)) {
        if (uri.getHost() == null || uri.getHost().isBlank()) {
          throw new IllegalArgumentException();
        }
      } else if (!"gvchat".equalsIgnoreCase(scheme)) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException ex) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "invalid_request", "callbackUrl must be HTTPS or gvchat:// without fragment");
    }
  }

  private boolean hasScope(String scope, String requiredScope) {
    return normalizeScopes(scope).contains(requiredScope);
  }

  private String maskPhone(String phone) {
    if (phone == null || phone.isBlank()) {
      return "";
    }
    if (phone.length() <= 7) {
      return phone.charAt(0) + "****";
    }
    return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }

  private List<String> normalizeScopes(List<String> scopes) {
    if (scopes == null) {
      return List.of();
    }
    return scopes.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
  }

  private List<String> normalizeScopes(String scope) {
    if (scope == null || scope.isBlank()) {
      return List.of();
    }
    return normalizeScopes(List.of(scope.trim().split("\\s+")));
  }

  private String randomHex(int length) {
    byte[] bytes = new byte[length / 2];
    SECURE_RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
