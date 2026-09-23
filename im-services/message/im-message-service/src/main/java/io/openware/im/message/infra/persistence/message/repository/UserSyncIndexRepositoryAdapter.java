package io.openware.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.message.domain.message.model.UserSyncIndex;
import io.openware.im.message.domain.message.repository.UserSyncIndexRepository;
import io.openware.im.message.infra.persistence.message.mapper.UserSyncIndexMapper;
import io.openware.im.message.infra.persistence.message.po.UserSyncIndexPo;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserSyncIndexRepositoryAdapter implements UserSyncIndexRepository {
  private final UserSyncIndexMapper mapper;

  @Override
  public long nextSequence(long userId) {
    mapper.initializeSequence(userId);
    Long current = mapper.lockSequence(userId);
    if (current == null) {
      throw new IllegalStateException("User synchronization sequence is unavailable.");
    }
    long next = current + 1;
    if (mapper.updateSequence(userId, next) != 1) {
      throw new IllegalStateException("Failed to update user synchronization sequence.");
    }
    return next;
  }

  @Override
  public void saveAll(List<UserSyncIndex> indexes) {
    for (UserSyncIndex index : indexes) {
      UserSyncIndexPo po = new UserSyncIndexPo();
      po.setUserId(index.userId());
      po.setSyncSeq(index.syncSeq());
      po.setMsgId(index.msgId());
      po.setConversationId(index.conversationId());
      po.setCreatedAt(index.createdAt());
      mapper.insert(po);
    }
  }

  @Override
  public List<UserSyncIndex> findAfter(long userId, long afterSyncSeq, int limit) {
    return mapper.findAfter(userId, afterSyncSeq, limit).stream()
        .map(po -> new UserSyncIndex(po.getUserId(), po.getSyncSeq(), po.getMsgId(), po.getConversationId(), po.getCreatedAt()))
        .toList();
  }

  @Override
  public List<Long> findRecipientUserIdsByMsgId(String msgId) {
    return mapper.findRecipientUserIdsByMsgId(msgId);
  }

  @Override
  public Set<String> findOwnedMessageIds(long userId, Collection<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) return Set.of();
    return mapper.findOwnedMessageIds(userId, msgIds).stream().collect(Collectors.toSet());
  }

  @Override
  public void deleteByMsgId(String msgId) {
    mapper.delete(Wrappers.<UserSyncIndexPo>lambdaQuery().eq(UserSyncIndexPo::getMsgId, msgId));
  }
}
