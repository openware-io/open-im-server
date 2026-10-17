package io.openware.im.message.domain.secretgroupmessage.repository;

import io.openware.im.message.domain.secretgroupmessage.model.SecretGroupMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 私密群聊消息仓储端口。
 *
 * <p><b>为什么读方法返回 {@link Persisted}</b>：领域模型不承载投递时刻（standards/10），
 * 而响应要回显它，所以端口把「业务聚合 + 记账快照」一起交给上层：只需业务事实的调用方读 {@code aggregate()}，
 * 装配响应的调用方再读 {@code audit()}。
 *
 * <p><b>为什么 {@code save} 也返回它</b>：写入后通常立刻装配响应，回读一次是浪费。
 */
public interface SecretGroupMessageRepository {
  Persisted<SecretGroupMessage> save(SecretGroupMessage message);

  long nextSeq(long secretGroupId);

  /** 游标拉取某接收方的密文（按 seq 升序）。 */
  List<Persisted<SecretGroupMessage>> listAfterSeq(long secretGroupId, long recipientUserId, long afterSeq,
      int limit);

  /** 硬删除某接收方的一份密文（按群 + msgId + 接收方）；返回删除行数。 */
  int deleteByMsgIdAndRecipient(long secretGroupId, String msgId, long recipientUserId);

  /** 硬删除某 msgId 的全部接收方密文（撤回/删除协调）；返回删除行数。 */
  int deleteByMsgId(long secretGroupId, String msgId);

  /** 按群 + msgId 查找任意一份密文（用于撤回/删除的发送方鉴权）。 */
  Optional<Persisted<SecretGroupMessage>> findByMsgId(long secretGroupId, String msgId);

  /** 已读计时候选：某接收方视角、由对方发送、active 且未计时的消息。 */
  List<Persisted<SecretGroupMessage>> findUncountedReadBy(long secretGroupId, long recipientUserId, long afterSeq,
      long viewerId);

  /** 某接收方 active 消息中最早的 destroyAt（无计时消息返回 null）。 */
  LocalDateTime findEarliestDestroyAt(long secretGroupId, long recipientUserId);

  /** 定时销毁扫描：返回已到销毁时间且仍为 active 的消息。 */
  List<Persisted<SecretGroupMessage>> findExpired(LocalDateTime now, int limit);
}
