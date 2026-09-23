package io.openware.im.user.application.account;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.model.UserAuthenticationSnapshot;
import io.openware.im.user.domain.account.model.UserSecurityQuestion;
import io.openware.im.user.domain.account.port.PasswordHasher;
import io.openware.im.user.domain.account.port.PasswordResetMailSender;
import io.openware.im.user.domain.account.port.PasswordResetSmsSender;
import io.openware.im.user.domain.account.port.PasswordResetTokenStore;
import io.openware.im.user.domain.account.port.UserAuthenticationProjectionPort;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.account.repository.UserSecurityQuestionRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 密码找回应用服务：支持邮箱、手机短信、密保问题三种找回方式。
 *
 * <p>安全约束：不泄露邮箱/手机号是否注册（无论是否存在账号都返回成功）；令牌/验证码短时有效且一次性使用；
 * 重置成功后使旧认证失效（提升 statusVersion + 广播认证失效事件）。密保问题答案以 bcrypt 哈希存储，
 * 校验失败与未设置返回同一错误，避免答案探测。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordRecoveryApplicationService {
  private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
  private static final Duration SMS_CODE_TTL = Duration.ofMinutes(5);
  private static final int QUESTION_MAX_LENGTH = 128;

  private final UserAccountRepository userAccountRepository;
  private final PasswordHasher passwordHasher;
  private final PasswordResetTokenStore tokenStore;
  private final PasswordResetMailSender mailSender;
  private final PasswordResetSmsSender smsSender;
  private final UserSecurityQuestionRepository securityQuestionRepository;
  private final UserAuthenticationProjectionPort authenticationUserProjection;
  private final UserStatusEventOutbox userStatusEventOutbox;

  @Value("${im.mail.reset-link-base:http://localhost:8080}")
  private String resetLinkBase;

  /** 请求邮箱找回：生成短时令牌并发重置邮件；不泄露邮箱是否注册。 */
  public void requestReset(String email) {
    String normalized = email == null ? "" : email.trim().toLowerCase();
    if (normalized.isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Email is required");
    }
    userAccountRepository.findByEmail(normalized).ifPresent(account -> {
      String token = UUID.randomUUID().toString();
      tokenStore.put(token, account.getId(), TOKEN_TTL);
      String resetLink = resetLinkBase + "/reset-password?token=" + token;
      mailSender.send(normalized, resetLink);
      log.info("[password-reset] issued reset token for userId={}", account.getId());
    });
  }

  /** 请求手机短信找回：生成 6 位验证码并下发短信；不泄露手机号是否注册。 */
  public void requestResetBySms(String phone) {
    String normalized = phone == null ? "" : phone.trim();
    if (normalized.isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Phone is required");
    }
    userAccountRepository.findByPhone(normalized).ifPresent(account -> {
      String code = generateSmsCode();
      tokenStore.put(code, account.getId(), SMS_CODE_TTL);
      smsSender.sendVerificationCode(normalized, code);
      log.info("[password-reset] issued reset sms code for userId={}", account.getId());
    });
  }

  /** 凭手机验证码重置密码：校验验证码 → 换新密码 → 使旧认证失效。 */
  @Transactional
  public void resetPasswordBySms(String phone, String code, String newPassword) {
    if (code == null || code.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Verification code is required");
    }
    Long userId = tokenStore.findUserId(code)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid or expired verification code"));
    resetPasswordForUser(userId, newPassword);
    tokenStore.remove(code);
  }

  /** 凭令牌重置密码（邮箱找回第二步、密保问题可复用）：校验令牌 → 换新密码 → 使旧认证失效。 */
  @Transactional
  public void resetPassword(String token, String newPassword) {
    if (token == null || token.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Reset token is required");
    }
    Long userId = tokenStore.findUserId(token)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.UNAUTHORIZED, "Invalid or expired reset token"));
    resetPasswordForUser(userId, newPassword);
    tokenStore.remove(token);
  }

  /** 设置（或更新）密保问题：答案以 bcrypt 哈希存储；每用户至多一条记录。 */
  @Transactional
  public void setSecurityQuestion(Long userId, String question, String answer) {
    validateQuestion(question, answer);
    String answerHash = passwordHasher.hash(answer.trim());
    LocalDateTime now = LocalDateTime.now();
    UserSecurityQuestion entity = securityQuestionRepository.findByUserId(userId)
        .map(existing -> {
          existing.update(question.trim(), answerHash, now);
          return existing;
        })
        .orElseGet(() -> UserSecurityQuestion.set(userId, question.trim(), answerHash, now));
    securityQuestionRepository.save(entity);
  }

  /** 密保问题找回密码：校验问题与答案后直接换新密码并失效旧认证；失败与未设置返回同一错误。 */
  @Transactional
  public void resetPasswordBySecurityQuestion(String username, String question, String answer, String newPassword) {
    if (username == null || username.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Username is required");
    }
    UserAccount account = userAccountRepository.findByUsername(username)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.FORBIDDEN, "Security question verification failed"));
    UserSecurityQuestion stored = securityQuestionRepository.findByUserId(account.getId()).orElse(null);
    if (stored == null || !stored.matchesQuestion(question) || !passwordHasher.matches(answer == null ? "" : answer,
        stored.getAnswerHash())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Security question verification failed");
    }
    account.changePassword(passwordHasher.hash(newPassword), LocalDateTime.now());
    invalidateAuthentication(account);
    log.info("[password-reset] password reset by security question for userId={}", account.getId());
  }

  private void resetPasswordForUser(Long userId, String newPassword) {
    UserAccount account = userAccountRepository.findById(userId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
    account.changePassword(passwordHasher.hash(newPassword), LocalDateTime.now());
    invalidateAuthentication(account);
    log.info("[password-reset] password reset for userId={}", userId);
  }

  private void invalidateAuthentication(UserAccount account) {
    userAccountRepository.save(account);
    authenticationUserProjection.save(new UserAuthenticationSnapshot(
        account.getId(), account.getUsername(), account.getRole(),
        account.getStatus() != UserAccountStatus.DISABLED, account.getStatusVersion()));
    userStatusEventOutbox.append(new UserAuthenticationInvalidated(
        UUID.randomUUID().toString(), account.getId(), account.getStatusVersion(), null, Instant.now()));
  }

  private static void validateQuestion(String question, String answer) {
    if (question == null || question.isBlank() || question.trim().length() > QUESTION_MAX_LENGTH) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Security question is required (max 128 chars)");
    }
    if (answer == null || answer.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Security answer is required");
    }
  }

  private static String generateSmsCode() {
    return String.valueOf(100000 + new SecureRandom().nextInt(900000));
  }
}
