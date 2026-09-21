package com.gvchat.im.message.infra.messaging.secretdestroy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.im.message.domain.secretmessage.port.SecretDestroyDelayedPublisher;
import com.gvchat.protocol.mq.event.SecretMessageDestroyCommand;
import com.gvchat.protocol.mq.topic.ImMqTopics;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 私密消息延迟销毁发布适配器：通过 RocketMQ 延迟消息实现「已读后 ttl 到期精确销毁」。 */
@Component
@Slf4j
public class SecretDestroyDelayedPublisherAdapter implements SecretDestroyDelayedPublisher {
  private final MqProducer mqProducer;
  private final ObjectMapper objectMapper;

  public SecretDestroyDelayedPublisherAdapter(MqProducer mqProducer, ObjectMapper objectMapper) {
    this.mqProducer = mqProducer;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean publish(long secretChatId, List<String> msgIds, int delaySeconds) {
    try {
      SecretMessageDestroyCommand command = SecretMessageDestroyCommand.builder()
          .commandId(UUID.randomUUID().toString())
          .secretChatId(secretChatId)
          .msgIds(msgIds)
          .destroyAt(Instant.now().plusSeconds(delaySeconds))
          .build();
      boolean sent = mqProducer.sendOrderedDelayed(
          ImMqTopics.SECRET_MESSAGE_DESTROY_COMMAND,
          "conv:secret:" + secretChatId,
          command.getCommandId(),
          objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8),
          delaySeconds);
      if (!sent) {
        log.info(
            "Secret destroy delay exceeds MQ limit, fallback to scan, secretChatId={}, delaySeconds={}",
            secretChatId, delaySeconds);
      }
      return sent;
    } catch (Exception ex) {
      log.warn("Failed to publish secret destroy delayed command, secretChatId={}, fallback to scan",
          secretChatId, ex);
      return false;
    }
  }
}
