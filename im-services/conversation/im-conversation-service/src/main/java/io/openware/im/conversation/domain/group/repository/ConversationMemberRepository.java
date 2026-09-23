package io.openware.im.conversation.domain.group.repository;

import io.openware.im.conversation.domain.group.model.ConversationMember;
import java.util.List;
import java.util.Optional;

public interface ConversationMemberRepository {
  ConversationMember save(ConversationMember member);
  Optional<ConversationMember> findByGroupIdAndUserId(long groupId, long userId);
  List<ConversationMember> findByGroupId(long groupId);
  void delete(long groupId, long userId);
}
