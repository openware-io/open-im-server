package io.openware.im.conversation.domain.secretchat.repository;

import io.openware.im.conversation.domain.secretchat.model.SecretChat;
import java.util.List;
import java.util.Optional;

/**
 * 私密聊天仓储端口。
 *
 * <p><b>为什么读方法返回 {@link Persisted}</b>：领域模型不承载审计列（standards/10 §聚合装配与持久化契约），
 * 而 {@code SecretChatResult} 要回显四个审计字段。端口因此把「业务聚合 + 记账快照」一起交给上层：
 * 只需要业务事实的调用方读 {@code aggregate()}，要装配响应的调用方再读 {@code audit()}。
 * {@code save} 仍返回裸聚合——写入时审计值由适配器确定，调用方不需要回读。
 */
public interface SecretChatRepository {
  /** 落库并返回「业务聚合 + 记账快照」：调用方通常要立刻用它装配响应，回读一次是浪费。 */
  Persisted<SecretChat> save(SecretChat secretChat);

  Optional<Persisted<SecretChat>> findById(long id);

  Optional<Persisted<SecretChat>> findBetween(long userA, long userB);

  /** 统计两人之间已存在的私密会话数量（用于「最多 N 个」上限校验）。 */
  long countBetween(long userA, long userB);

  List<Persisted<SecretChat>> findByParticipant(long userId);

  /** 硬删除会话（任意一方删除即会话终止，双方列表都不再返回）。 */
  void deleteById(long id);
}
