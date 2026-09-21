package com.gvchat.im.conversation.domain.group.repository;

import com.gvchat.im.conversation.domain.group.model.ConversationGroup;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConversationGroupRepository {
  ConversationGroup save(ConversationGroup group);
  Optional<ConversationGroup> findById(long groupId);
  default Optional<ConversationGroup> findByIdForUpdate(long groupId) {
    return findById(groupId);
  }
  List<ConversationGroup> findActiveByUserId(long userId);
  long count();
  long countCreatedSince(java.time.LocalDateTime since);

  /** 按天统计新建群组数（返回 [{date:'yyyy-MM-dd', count:N}]，按日期升序），用于趋势图。 */
  List<Map<String, Object>> dailyCounts(java.time.LocalDateTime since);

  /** 管理端分页检索群组（按名称关键字，最近创建在前）。 */
  List<ConversationGroup> search(String keyword, long offset, long limit);
  long countByKeyword(String keyword);
}
