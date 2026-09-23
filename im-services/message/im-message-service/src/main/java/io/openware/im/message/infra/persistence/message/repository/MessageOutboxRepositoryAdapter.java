package io.openware.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.message.domain.message.model.MessageOutbox;
import io.openware.im.message.domain.message.repository.MessageOutboxRepository;
import io.openware.im.message.infra.persistence.message.MessagePersistenceConverter;
import io.openware.im.message.infra.persistence.message.mapper.MessageOutboxMapper;
import io.openware.im.message.infra.persistence.message.po.MessageOutboxPo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MessageOutboxRepositoryAdapter implements MessageOutboxRepository {
  private final MessageOutboxMapper mapper;

  @Override
  public MessageOutbox save(MessageOutbox outbox) {
    MessageOutboxPo po = MessagePersistenceConverter.toPo(outbox);
    if (po.getId() == null) {
      mapper.insert(po);
      outbox.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return outbox;
  }

  @Override
  public List<MessageOutbox> findPending(int batchSize) {
    return mapper.selectList(Wrappers.<MessageOutboxPo>lambdaQuery().eq(MessageOutboxPo::getPublished, false)
        .orderByAsc(MessageOutboxPo::getId).last("LIMIT " + batchSize)).stream()
        .map(MessagePersistenceConverter::toDomain).toList();
  }
}
