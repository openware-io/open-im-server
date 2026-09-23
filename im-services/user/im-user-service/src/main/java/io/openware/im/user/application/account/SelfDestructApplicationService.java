package io.openware.im.user.application.account;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.im.user.application.account.result.SelfDestructPolicyResult;
import io.openware.im.user.domain.account.event.UserChatRecordsPurged;
import io.openware.im.user.domain.account.model.SelfDestructPolicy;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号自毁应用服务：读取/设置自毁策略，以及到期账号的聊天记录清理清扫。
 *
 * <p>自毁策略（off/1mo/3mo/6mo/1yr）由用户自行设置；每次登录会刷新最近活跃时间并顺延到期截止。
 * 清扫任务发现 {@code selfDestructAt <= now} 的账号后**仅清理其聊天记录**（消息/会话/私密会话等），
 * **保留账号**，并将策略重置为 off，同时广播 {@link UserChatRecordsPurged} 供消息/会话服务清理。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfDestructApplicationService {
  private static final Set<String> ALLOWED_POLICIES = Set.of("off", "1mo", "3mo", "6mo", "1yr");

  private final UserAccountRepository userAccountRepository;
  private final UserStatusEventOutbox userStatusEventOutbox;

  @Transactional(readOnly = true)
  public SelfDestructPolicyResult getPolicy(Long userId) {
    UserAccount account = requireAccount(userId);
    return toResult(account);
  }

  @Transactional
  public SelfDestructPolicyResult setPolicy(Long userId, String policy) {
    if (policy == null || !ALLOWED_POLICIES.contains(policy.trim().toLowerCase())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST,
          "Invalid self destruct policy, expected one of " + ALLOWED_POLICIES);
    }
    UserAccount account = requireAccount(userId);
    account.setSelfDestructPolicy(SelfDestructPolicy.fromValue(policy), LocalDateTime.now());
    return toResult(userAccountRepository.save(account));
  }

  /** 清扫到期账号：清理其聊天记录（保留账号），重置策略，广播清理事件；返回本次清理数量。 */
  @Transactional
  public int sweepExpired(LocalDateTime now, int limit) {
    List<UserAccount> due = userAccountRepository.findSelfDestructDue(now, limit);
    for (UserAccount account : due) {
      Long userId = account.getId();
      account.setSelfDestructPolicy(SelfDestructPolicy.OFF, LocalDateTime.now());
      userAccountRepository.save(account);
      userStatusEventOutbox.append(new UserChatRecordsPurged(UUID.randomUUID().toString(), userId, Instant.now()));
      log.info("Purged chat records for self destruct policy, account kept, userId={}, policy={}",
          userId, account.getSelfDestructPolicy().value());
    }
    return due.size();
  }

  private UserAccount requireAccount(Long userId) {
    return userAccountRepository.findById(userId)
        .orElseThrow(() -> new ApiException(HttpStatusCodes.NOT_FOUND, "User not found"));
  }

  private SelfDestructPolicyResult toResult(UserAccount account) {
    return new SelfDestructPolicyResult(
        account.getSelfDestructPolicy().value(), account.getSelfDestructAt(), account.getLastLoginAt());
  }
}
