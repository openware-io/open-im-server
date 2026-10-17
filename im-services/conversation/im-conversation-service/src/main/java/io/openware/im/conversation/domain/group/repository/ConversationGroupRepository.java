package io.openware.im.conversation.domain.group.repository;

import io.openware.im.conversation.domain.group.model.ConversationGroup;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConversationGroupRepository {
  /** 新增或更新群组；审计列由适配器按 {@code occurredAt} 填充。 */
  Persisted<ConversationGroup> save(ConversationGroup group, java.time.LocalDateTime occurredAt);

  Optional<Persisted<ConversationGroup>> findById(long groupId);

  default Optional<Persisted<ConversationGroup>> findByIdForUpdate(long groupId) {
    return findById(groupId);
  }

  List<Persisted<ConversationGroup>> findActiveByUserId(long userId);

  long count();

  long countCreatedSince(java.time.LocalDateTime since);

  /** 按天统计新建群组数（返回 [{date:'yyyy-MM-dd', count:N}]，按日期升序），用于趋势图。 */
  List<Map<String, Object>> dailyCounts(java.time.LocalDateTime since);

  /** 管理端分页检索群组（按名称关键字，最近创建在前）。 */
  List<Persisted<ConversationGroup>> search(String keyword, long offset, long limit);

  long countByKeyword(String keyword);
}
