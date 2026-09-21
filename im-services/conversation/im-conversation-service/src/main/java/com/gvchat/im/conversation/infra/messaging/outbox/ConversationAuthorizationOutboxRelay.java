package com.gvchat.im.conversation.infra.messaging.outbox;

import com.gvchat.im.conversation.domain.group.repository.ConversationOutboxRepository;
import com.gvchat.infrastructure.mq.MqProducer;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ConversationAuthorizationOutboxRelay {
  private final ConversationOutboxRepository outboxRepository;
  private final MqProducer mqProducer;

  @Scheduled(fixedDelayString = "${im.conversation.outbox.relay-delay-ms:1000}")
  @Transactional
  public void relayPendingEvents() {
    for (ConversationOutboxRepository.PendingEvent event : outboxRepository.findPending(100)) {
      try {
        mqProducer.sendOrdered(event.topic(), event.shardingKey(), event.aggregateId(),
            event.payloadJson().getBytes(StandardCharsets.UTF_8));
        outboxRepository.markPublished(event.id());
      } catch (Exception ex) {
        throw new IllegalStateException("Unable to relay conversation authorization event", ex);
      }
    }
  }
}
