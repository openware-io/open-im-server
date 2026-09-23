package io.openware.im.user.application.account;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.common.port.TokenProvider;
import io.openware.im.user.application.account.command.LoginCommand;
import io.openware.im.user.application.account.command.RegisterAccountCommand;
import io.openware.im.user.application.account.command.ChangeUserStatusCommand;
import io.openware.im.user.application.account.result.AuthenticatedAccountResult;
import io.openware.im.user.application.account.result.ChangeUserStatusResult;
import io.openware.im.user.application.device.DeviceSessionApplicationService;
import io.openware.im.user.application.device.command.RecordDeviceSessionCommand;
import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.event.UserStatusChanged;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.model.UserStatusOperation;
import io.openware.im.user.domain.account.model.UserAuthenticationSnapshot;
import io.openware.im.user.domain.account.port.PasswordHasher;
import io.openware.im.user.domain.account.port.UserAuthenticationProjectionPort;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.account.repository.UserStatusOperationRepository;
import io.openware.im.user.domain.device.model.LoginMethod;
import io.openware.im.user.infra.security.IdaasSsoClient;
import io.openware.im.user.media.MediaReferenceClient;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountApplicationService {
  private final UserAccountRepository userAccountRepository;
  private final TokenProvider tokenProvider;
  private final PasswordHasher passwordHasher;
  private final UserStatusOperationRepository userStatusOperationRepository;
  private final UserStatusEventOutbox userStatusEventOutbox;
  private final UserAuthenticationProjectionPort authenticationUserProjection;
  private final MediaReferenceClient mediaReferences;
  private final IdaasSsoClient idaasSsoClient;
  private final DeviceSessionApplicationService deviceSessionApplicationService;

  @Transactional
  public AuthenticatedAccountResult register(RegisterAccountCommand command) {
    if (userAccountRepository.existsByUsername(command.username())) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Username already exists");
    }
    // 邮箱为找回密码的依据：必填并归一化（trim + 小写），与 PasswordRecovery 的查找口径一致。
    // 当前版本不验证邮箱归属（无验证码/确认邮件），仅做非空与格式校验，方便注册。
    if (command.email() == null || command.email().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Email is required");
    }
    String normalizedEmail = command.email().trim().toLowerCase();
    if (userAccountRepository.existsByEmail(normalizedEmail)) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Email already exists");
    }

    UserAccount account = UserAccount.register(
        command.username(), passwordHasher.hash(command.password()), command.nickname(), normalizedEmail,
        command.phone(), LocalDateTime.now());
    UserAccount savedAccount = userAccountRepository.save(account);
    updateAuthenticationProjection(savedAccount);
    return toAuthenticatedResult(savedAccount);
  }

  @Transactional
  public AuthenticatedAccountResult login(LoginCommand command) {
    UserAccount account = userAccountRepository.findByUsername(command.username())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid credentials"));
    if (!passwordHasher.matches(command.password(), account.getPasswordHash())) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid credentials");
    }
    if (account.getStatus() == UserAccountStatus.DISABLED) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Account disabled");
    }
    // 登录即刷新最近活跃时间，并按当前自毁策略重算到期截止（活跃即续期）。
    account.recordLogin(LocalDateTime.now());
    userAccountRepository.save(account);
    updateAuthenticationProjection(account);
    recordDeviceSession(account.getId(), command.deviceId(), command.deviceType(), command.deviceName(),
        LoginMethod.PASSWORD, command.ip());
    return toAuthenticatedResult(account);
  }

  @Transactional
  public AuthenticatedAccountResult loginByQr(long userId, String ip) {
    UserAccount account = userAccountRepository.findById(userId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
    if (account.getStatus() == UserAccountStatus.DISABLED) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Account disabled");
    }
    account.recordLogin(LocalDateTime.now());
    userAccountRepository.save(account);
    updateAuthenticationProjection(account);
    // 扫码端未显式上报 deviceId 时，以「qr:userId」占位，保证同用户扫码登录复用同一条会话。
    recordDeviceSession(account.getId(), "qr-" + account.getId(), "web", null, LoginMethod.QR_CODE, ip);
    return toAuthenticatedResult(account);
  }

  /**
   * SSO 免二次登录：校验 IDaaS Token，按 username 映射本地管理员账号并签发 IM 后台 Token。
   * 最简映射（同 username）；账号不存在时返回 404 明确错误。
   */
  @Transactional
  public AuthenticatedAccountResult ssoLogin(String ticket, String ip) {
    IdaasSsoClient.SsoUser ssoUser = idaasSsoClient.verifyTicket(ticket);
    String username = ssoUser.username();
    UserAccount account = userAccountRepository.findByUsername(username)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "SSO_ADMIN_ACCOUNT_NOT_FOUND",
            "no IM admin account mapped for IDaaS username: " + username));
    if (account.getStatus() == UserAccountStatus.DISABLED) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "SSO_ADMIN_ACCOUNT_DISABLED",
          "IM admin account disabled: " + username);
    }
    account.recordLogin(LocalDateTime.now());
    userAccountRepository.save(account);
    updateAuthenticationProjection(account);
    recordDeviceSession(account.getId(), "sso-" + account.getId(), "web", null, LoginMethod.SSO, ip);
    Duration ttl = ssoUser.expiresAt() > 0
        ? Duration.ofMillis(Math.max(1000L, ssoUser.expiresAt() - System.currentTimeMillis()))
        : null;
    return toAuthenticatedResult(account, ttl);
  }

  @Transactional
  public ChangeUserStatusResult changeStatus(ChangeUserStatusCommand command) {
    validateStatusCommand(command);
    return userStatusOperationRepository.findByIdempotencyKey(command.idempotencyKey())
        .map(this::toChangeUserStatusResult)
        .orElseGet(() -> changeStatusOnce(command));
  }

  private ChangeUserStatusResult changeStatusOnce(ChangeUserStatusCommand command) {
    UserAccount account = userAccountRepository.findById(command.userId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
    if (account.getStatusVersion() != command.expectedStatusVersion()) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "User status version conflict");
    }
    UserAccountStatus previousStatus = account.getStatus();
    LocalDateTime occurredAt = LocalDateTime.now();
    boolean changed = account.changeStatus(command.status(), command.operatorId(), occurredAt);
    if (changed && !userAccountRepository.saveStatusIfVersionMatches(account, command.expectedStatusVersion())) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "User status version conflict");
    }
    ChangeUserStatusResult result = new ChangeUserStatusResult(command.requestVersion(), account.getId(), previousStatus,
        account.getStatus(), account.getStatusVersion(), command.idempotencyKey(), command.correlationId(), occurredAt);
    userStatusOperationRepository.save(toUserStatusOperation(result), command.expectedStatusVersion(), command.operatorId(),
        command.reason());
    if (changed) {
      updateAuthenticationProjection(account);
      userStatusEventOutbox.append(new UserStatusChanged(UUID.randomUUID().toString(), account.getId(), previousStatus,
          account.getStatus(), account.getStatusVersion(), command.operatorId(), command.reason(), command.correlationId(),
          Instant.now()));
      if (account.getStatus() == UserAccountStatus.DISABLED) {
        userStatusEventOutbox.append(new UserAuthenticationInvalidated(UUID.randomUUID().toString(), account.getId(),
            account.getStatusVersion(), command.correlationId(), Instant.now()));
      }
    }
    return result;
  }

  private void validateStatusCommand(ChangeUserStatusCommand command) {
    if (command.requestVersion() != 1) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Unsupported request version");
    }
  }

  private void recordDeviceSession(Long userId, String deviceId, String deviceType, String deviceName,
      LoginMethod method, String ip) {
    deviceSessionApplicationService.recordLogin(
        new RecordDeviceSessionCommand(userId, deviceId, deviceType, deviceName, method, ip));
  }

  private AuthenticatedAccountResult toAuthenticatedResult(UserAccount account) {
    return toAuthenticatedResult(account, null);
  }

  private AuthenticatedAccountResult toAuthenticatedResult(UserAccount account, Duration ttl) {
    String accessToken = ttl == null
        ? tokenProvider.createAccessToken(account.getId(), account.getUsername(), account.getStatusVersion())
        : tokenProvider.createAccessToken(account.getId(), account.getUsername(), account.getStatusVersion(), ttl);
    return new AuthenticatedAccountResult(
        accessToken, account.getId(),
        account.getUsername(), account.getNickname(),
        mediaReferences.accessUrl(account.getId(), account.getAvatar(), account.getId()));
  }

  private UserStatusOperation toUserStatusOperation(ChangeUserStatusResult result) {
    return new UserStatusOperation(result.responseVersion(), result.userId(), result.previousStatus(), result.status(),
        result.statusVersion(), result.idempotencyKey(), result.correlationId(), result.occurredAt());
  }

  private ChangeUserStatusResult toChangeUserStatusResult(UserStatusOperation operation) {
    return new ChangeUserStatusResult(operation.requestVersion(), operation.userId(), operation.previousStatus(),
        operation.currentStatus(), operation.statusVersion(), operation.idempotencyKey(), operation.correlationId(),
        operation.occurredAt());
  }

  private void updateAuthenticationProjection(UserAccount account) {
    authenticationUserProjection.save(new UserAuthenticationSnapshot(
        account.getId(),
        account.getUsername(),
        account.getRole(),
        account.getStatus() != UserAccountStatus.DISABLED,
        account.getStatusVersion()));
  }
}
