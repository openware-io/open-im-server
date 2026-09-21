package com.gvchat.im.conversation.infra.persistence.outbox.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.im.conversation.domain.group.repository.ConversationOutboxRepository;
import com.gvchat.im.conversation.infra.persistence.outbox.mapper.ConversationOutboxMapper;
import com.gvchat.im.conversation.infra.persistence.outbox.po.ConversationOutboxPo;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisConversationOutboxRepository implements ConversationOutboxRepository {
  private final ConversationOutboxMapper mapper;

  @Override
  public void append(String eventId, String aggregateId, String topic, String shardingKey, String payloadJson) {
    ConversationOutboxPo po = new ConversationOutboxPo();
    po.setEventId(eventId); po.setAggregateId(aggregateId); po.setTopic(topic); po.setShardingKey(shardingKey);
    po.setPayloadJson(payloadJson); po.setPublished(false); po.setCreatedAt(LocalDateTime.now());
    mapper.insert(po);
  }

  @Override
  public List<PendingEvent> findPending(int limit) {
    return mapper.selectList(Wrappers.<ConversationOutboxPo>lambdaQuery().eq(ConversationOutboxPo::getPublished, false)
        .orderByAsc(ConversationOutboxPo::getId).last("LIMIT " + limit)).stream()
        .map(po -> new PendingEvent(po.getId(), po.getEventId(), po.getAggregateId(), po.getTopic(), po.getShardingKey(), po.getPayloadJson())).toList();
  }

  @Override
  public void markPublished(long id) {
    ConversationOutboxPo po = new ConversationOutboxPo();
    po.setId(id); po.setPublished(true); po.setPublishedAt(LocalDateTime.now()); mapper.updateById(po);
  }
}
