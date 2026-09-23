package io.openware.im.message.infra.persistence.message.repository;

import io.openware.im.message.domain.message.model.MessageReadStatus;
import io.openware.im.message.domain.message.repository.MessageReadStatusRepository;
import io.openware.im.message.infra.persistence.message.mapper.MessageReadStatusMapper;
import io.openware.im.message.infra.persistence.message.po.MessageReadStatusPo;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MessageReadStatusRepositoryAdapter implements MessageReadStatusRepository {
  private final MessageReadStatusMapper mapper;

  @Override
  public boolean saveIfAbsent(MessageReadStatus readStatus) {
    MessageReadStatusPo po = new MessageReadStatusPo();
    po.setMsgId(readStatus.msgId());
    po.setUserId(readStatus.userId());
    po.setReadAt(readStatus.readAt());
    try {
      mapper.insert(po);
      return true;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  @Override
  public Map<String, LocalDateTime> findReadAtByUserIdAndMsgIds(long userId, Collection<String> msgIds) {
    if (msgIds == null || msgIds.isEmpty()) {
      return Map.of();
    }
    return mapper.findByUserIdAndMsgIds(userId, msgIds).stream()
        .collect(Collectors.toMap(MessageReadStatusPo::getMsgId, MessageReadStatusPo::getReadAt,
            (first, ignored) -> first));
  }

  @Override
  public void deleteByMsgId(String msgId) {
    mapper.delete(Wrappers.<MessageReadStatusPo>lambdaQuery().eq(MessageReadStatusPo::getMsgId, msgId));
  }
}
