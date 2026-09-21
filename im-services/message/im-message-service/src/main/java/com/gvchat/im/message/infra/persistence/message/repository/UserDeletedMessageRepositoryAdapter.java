package com.gvchat.im.message.infra.persistence.message.repository;

import com.gvchat.common.enums.ChatType;
import com.gvchat.im.message.domain.message.repository.UserDeletedMessageRepository;
import com.gvchat.im.message.infra.persistence.message.mapper.UserDeletedMessageMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserDeletedMessageRepositoryAdapter implements UserDeletedMessageRepository {
  private final UserDeletedMessageMapper mapper;

  @Override
  public void markDeleted(long userId, String msgId, String conversationId, String chatType) {
    mapper.insertIgnore(userId, msgId, conversationId, chatType);
  }

  @Override
  public int markDeleted(long userId, List<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) {
      return 0;
    }
    int affected = 0;
    for (String msgId : msgIds) {
      if (msgId == null || msgId.isBlank()) {
        continue;
      }
      // conversation_id / chat_type 在删除请求里未必携带，这里允许为空串（表列 NOT NULL）。
      affected += mapper.insertIgnore(userId, msgId, "", ChatType.PRIVATE.name());
    }
    return affected;
  }

  @Override
  public List<DeletedMessage> findAllByUserId(long userId) {
    return mapper.findAllByUserId(userId).stream()
        .map(row -> new DeletedMessage(row.getMsgId(), row.getConversationId(), row.getChatType()))
        .toList();
  }

  @Override
  public List<String> findDeletedMsgIds(long userId, List<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) {
      return List.of();
    }
    return mapper.findDeletedMsgIds(userId, msgIds.stream().filter(id -> id != null && !id.isBlank()).toList());
  }

  @Override
  public void deleteByConversation(long userId, String conversationId) {
    mapper.deleteByConversation(userId, conversationId);
  }
}
