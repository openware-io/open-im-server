package io.openware.im.conversation.domain.secretgroupchat.repository;

import io.openware.im.conversation.domain.secretgroupchat.model.SecretGroupMember;
import java.util.List;
import java.util.Optional;

public interface SecretGroupMemberRepository {
  SecretGroupMember save(SecretGroupMember member);

  List<SecretGroupMember> findByGroupId(long groupId);

  Optional<SecretGroupMember> findByGroupIdAndUserId(long groupId, long userId);

  List<SecretGroupMember> findByUserId(long userId);

  void deleteByGroupId(long groupId);

  void deleteByGroupIdAndUserId(long groupId, long userId);
}
