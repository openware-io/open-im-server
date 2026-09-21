package com.gvchat.im.user.application.cancellation;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.user.application.cancellation.command.SubmitAccountCancellationCommand;
import com.gvchat.im.user.application.cancellation.result.AccountCancellationStatusResult;
import com.gvchat.im.user.application.cancellation.result.AccountCancellationStepResult;
import com.gvchat.im.user.application.cancellation.result.AccountCancellationSubmitResult;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.port.PasswordHasher;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellation;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellationAction;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellationLog;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellationStep;
import com.gvchat.im.user.domain.cancellation.repository.AccountCancellationLogRepository;
import com.gvchat.im.user.domain.cancellation.repository.AccountCancellationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号注销申请应用服务：认证用户提交申请、查询状态，硬删由 {@link AccountCancellationProcessor} 异步执行。 */
@Service
@RequiredArgsConstructor
public class AccountCancellationApplicationService {
  private final UserAccountRepository userAccountRepository;
  private final PasswordHasher passwordHasher;
  private final AccountCancellationRepository accountCancellationRepository;
  private final AccountCancellationLogRepository logRepository;

  /** 仅认证用户可申请注销；申请前再次校验密码，并返回用于轮询状态的一次性令牌。 */
  @Transactional
  public AccountCancellationSubmitResult submit(SubmitAccountCancellationCommand command) {
    UserAccount account = userAccountRepository.findById(command.userId())
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
    if (account.getStatus() == UserAccountStatus.DISABLED) {
      throw new ApiException(HttpStatusCodes.GONE, "Account already cancelled");
    }
    if (!passwordHasher.matches(command.password(), account.getPasswordHash())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Password incorrect");
    }
    if (accountCancellationRepository.findActiveByUserId(account.getId()).isPresent()) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "Account cancellation already in progress");
    }

    LocalDateTime now = LocalDateTime.now();
    String token = randomToken();
    AccountCancellation application = AccountCancellation.request(
        account.getId(), account.getUsername(), account.getNickname(), account.getPhone(), account.getEmail(),
        sha256Hex(token), command.source(), command.ip(), now);
    accountCancellationRepository.save(application);
    logRepository.save(AccountCancellationLog.create(application.getId(), account.getId(),
        AccountCancellationAction.REQUESTED, "Account cancellation requested, source=" + command.source(),
        "USER", account.getId(), command.ip(), now));
    return new AccountCancellationSubmitResult(application.getId(), application.getStatus().name(), token);
  }

  @Transactional(readOnly = true)
  public AccountCancellationStatusResult getStatus(Long applicationId, String token) {
    AccountCancellation application = accountCancellationRepository.findById(applicationId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "Cancellation application not found"));
    if (!sha256Hex(token == null ? "" : token).equals(application.getStatusTokenHash())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "Invalid status token");
    }
    List<AccountCancellationStepResult> steps = Arrays.stream(AccountCancellationStep.values())
        .map(step -> new AccountCancellationStepResult(step.code(), application.getCompletedSteps().contains(step)))
        .toList();
    return new AccountCancellationStatusResult(application.getId(), application.getStatus().name(), steps,
        application.getRequestedAt(), application.getCompletedAt(), application.getFailureReason());
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
