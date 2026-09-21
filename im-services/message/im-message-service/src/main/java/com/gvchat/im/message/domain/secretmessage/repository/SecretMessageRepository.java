package com.gvchat.im.message.domain.secretmessage.repository;

import com.gvchat.im.message.domain.secretmessage.model.SecretMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SecretMessageRepository {
  SecretMessage save(SecretMessage message);

  long nextSeq(long secretChatId);

  List<SecretMessage> listAfterSeq(long secretChatId, long afterSeq, int limit);

  /** 按会话 + msgId 查消息（撤回/删除协调用）。 */
  Optional<SecretMessage> findByMsgId(long secretChatId, String msgId);

  /**
   * 硬删除密文消息（按会话 + msgId）；返回删除行数（0 = 已不存在，用于并发/幂等协调）。
   * 撤回、删除、定时销毁统一硬删除密文本体，销毁痕迹另见 {@code SecretMessageDestroyedRepository}。
   */
  int deleteByMsgId(long secretChatId, String msgId);

  /** 硬删除某个私密会话的全部密文（会话终止时调用）；返回删除行数。 */
  int deleteBySecretChatId(long secretChatId);

  /**
   * 已读计时候选：secretChatId 内 {@code seq <= afterSeq}、由 {@code viewerId} 之外
   * 的用户发送、状态 active 且尚未设置 destroyAt 的消息（按 seq 升序）。
   */
  List<SecretMessage> findUncountedReadBy(long secretChatId, long afterSeq, long viewerId);

  /** 会话内 active 且尚未设置 destroyAt 的消息（策略变更回补计时用，不区分已读/未读，按 seq 升序）。 */
  List<SecretMessage> findActiveWithoutDestroyAt(long secretChatId);

  /** 会话内 active 消息中最早的 destroyAt（无计时消息返回 null）。 */
  LocalDateTime findEarliestDestroyAt(long secretChatId);

  /**
   * 延迟销毁到期的候选：secretChatId 内 msgId 属于 [msgIds]、状态 active、
   * destroyAt 非空且不晚于 [now] 的消息。
   */
  List<SecretMessage> findActiveByMsgIds(long secretChatId, List<String> msgIds, LocalDateTime now);

  /** 定时销毁扫描：返回已到销毁时间且仍为 active 的消息（按销毁时间升序）。 */
  List<SecretMessage> findExpired(LocalDateTime now, int limit);
}
