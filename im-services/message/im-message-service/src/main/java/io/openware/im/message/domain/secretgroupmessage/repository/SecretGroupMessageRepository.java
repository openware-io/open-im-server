package io.openware.im.message.domain.secretgroupmessage.repository;

import io.openware.im.message.domain.secretgroupmessage.model.SecretGroupMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SecretGroupMessageRepository {
  SecretGroupMessage save(SecretGroupMessage message);

  long nextSeq(long secretGroupId);

  /** 游标拉取某接收方的密文（按 seq 升序）。 */
  List<SecretGroupMessage> listAfterSeq(long secretGroupId, long recipientUserId, long afterSeq, int limit);

  /** 硬删除某接收方的一份密文（按群 + msgId + 接收方）；返回删除行数。 */
  int deleteByMsgIdAndRecipient(long secretGroupId, String msgId, long recipientUserId);

  /** 硬删除某 msgId 的全部接收方密文（撤回/删除协调）；返回删除行数。 */
  int deleteByMsgId(long secretGroupId, String msgId);

  /** 按群 + msgId 查找任意一份密文（用于撤回/删除的发送方鉴权）。 */
  Optional<SecretGroupMessage> findByMsgId(long secretGroupId, String msgId);

  /** 已读计时候选：某接收方视角、由对方发送、active 且未计时的消息。 */
  List<SecretGroupMessage> findUncountedReadBy(long secretGroupId, long recipientUserId, long afterSeq, long viewerId);

  /** 某接收方 active 消息中最早的 destroyAt（无计时消息返回 null）。 */
  LocalDateTime findEarliestDestroyAt(long secretGroupId, long recipientUserId);

  /** 定时销毁扫描：返回已到销毁时间且仍为 active 的消息。 */
  List<SecretGroupMessage> findExpired(LocalDateTime now, int limit);
}
