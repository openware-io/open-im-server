package io.openware.im.user.application.cancellation;

import io.openware.im.user.domain.account.event.UserAuthenticationInvalidated;
import io.openware.im.user.domain.account.event.UserChatRecordsPurged;
import io.openware.im.user.domain.account.event.UserDataWipeRequested;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.port.PasswordHasher;
import io.openware.im.user.domain.account.port.UserAccountDataPurger;
import io.openware.im.user.domain.account.port.UserStatusEventOutbox;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.domain.cancellation.model.AccountCancellation;
import io.openware.im.user.domain.cancellation.model.AccountCancellationAction;
import io.openware.im.user.domain.cancellation.model.AccountCancellationLog;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStep;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationLogRepository;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注销处理服务：异步「墓碑化」账号并删除私有关联数据，同时广播聊天记录清理、认证失效与各端数据擦除事件。
 *
 * <p>账号主记录不物理删除，而是调用 {@link UserAccount#cancel} 置为 disabled 并把昵称改为「已注销用户」、
 * 清空邮箱/手机号/头像/签名，替换为随机密码与用户名。这样跨服务的群成员列表与消息投影在异步清理完成前
 * 仍能解析到「已注销用户」标识，而不是悬空引用；群成员关系与聊天记录由消息/会话服务消费
 * {@link UserChatRecordsPurged} 事件异步清理（优先 tombstone 保留消息顺序游标）。</p>
 *
 * <p>单条申请在一个事务内处理（软删 + 事件落 Outbox + 状态流转），失败则整体回滚并单独标记失败。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountCancellationProcessor {
  private static final int MAX_REASON_LENGTH = 200;

  private final AccountCancellationRepository accountCancellationRepository;
  private final AccountCancellationLogRepository logRepository;
  private final UserAccountRepository userAccountRepository;
  private final UserAccountDataPurger userAccountDataPurger;
  private final PasswordHasher passwordHasher;
  private final UserStatusEventOutbox userStatusEventOutbox;

  public List<Long> findPendingIds(int limit) {
    return accountCancellationRepository.findPending(limit).stream().map(AccountCancellation::getId).toList();
  }

  @Transactional
  public void processOne(Long id) {
    AccountCancellation application = accountCancellationRepository.findById(id).orElse(null);
    if (application == null || !application.isActive()) {
      return;
    }
    Long userId = application.getUserId();
    LocalDateTime now = LocalDateTime.now();
    Instant occurredAt = Instant.now();

    application.markProcessing(now);
    accountCancellationRepository.save(application);
    logRepository.save(AccountCancellationLog.create(application.getId(), userId,
        AccountCancellationAction.PROCESSING, "Start processing cancellation", "SYSTEM", null, null, now));

    UserAccount account = userAccountRepository.findById(userId).orElse(null);

    // 1. 硬删用户服务内私有关联数据（设备令牌/设备会话/好友/贴纸/设备密钥/设置/密保问题/旧 Outbox）。
    userAccountDataPurger.purge(userId);

    // 2. 账号墓碑化（软删）：保留主记录并置为 disabled + 「已注销用户」，清空个人可识别信息。
    if (account != null) {
      account.cancel(passwordHasher.hash(UUID.randomUUID().toString()), tombstoneUsername(userId), now);
      userAccountRepository.save(account);
    }
    long nextStatusVersion = account == null ? 1L : account.getStatusVersion();

    // 3. 广播事件：消息/会话服务清理聊天记录与群成员关系；接入层踢下线；各端 App 清理本地数据与缓存。
    userStatusEventOutbox.append(new UserChatRecordsPurged(UUID.randomUUID().toString(), userId, occurredAt));
    userStatusEventOutbox.append(new UserAuthenticationInvalidated(
        UUID.randomUUID().toString(), userId, nextStatusVersion, null, occurredAt));
    userStatusEventOutbox.append(new UserDataWipeRequested(UUID.randomUUID().toString(), userId, occurredAt));

    // 4. 完成全部删除步骤（聊天记录清理由事件异步驱动，此处标记事件已入 Outbox 即视为步骤完成）。
    application.complete(EnumSet.allOf(AccountCancellationStep.class), now);
    accountCancellationRepository.save(application);
    logRepository.save(AccountCancellationLog.create(application.getId(), userId,
        AccountCancellationAction.COMPLETED, "Account tombstoned and related data purged; client data wipe marked",
        "SYSTEM", null, null, now));
    log.info("Account cancellation completed, userId={}, cancellationId={}", userId, application.getId());
  }

  @Transactional
  public void markFailed(Long id, String reason) {
    AccountCancellation application = accountCancellationRepository.findById(id).orElse(null);
    if (application == null || !application.isActive()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    String trimmed = reason == null ? "Unknown error" : reason;
    if (trimmed.length() > MAX_REASON_LENGTH) {
      trimmed = trimmed.substring(0, MAX_REASON_LENGTH);
    }
    application.fail(trimmed, now);
    accountCancellationRepository.save(application);
    logRepository.save(AccountCancellationLog.create(application.getId(), application.getUserId(),
        AccountCancellationAction.FAILED, trimmed, "SYSTEM", null, null, now));
    log.warn("Account cancellation failed, userId={}, cancellationId={}, reason={}",
        application.getUserId(), application.getId(), trimmed);
  }

  /** 生成墓碑用户名：释放原用户名，同时保证唯一（追加用户 id 与随机段）。 */
  private static String tombstoneUsername(Long userId) {
    return "deleted_" + userId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
