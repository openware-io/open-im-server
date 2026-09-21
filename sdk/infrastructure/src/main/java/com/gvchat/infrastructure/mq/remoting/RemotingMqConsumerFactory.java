package com.gvchat.infrastructure.mq.remoting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqMessageHandler;
import com.gvchat.infrastructure.mq.MqProducer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * RocketMQ 有序消费者工厂。
 *
 * <p><b>为什么必须区分失败类型</b>：顺序消费的位点按队列推进，只要一条消息始终消费失败，该队列就会被
 * 无限挂起，位点不再前进，**该队列后续所有消息都会卡住且不落库**。因此一条「毒消息」足以让整条分片
 * 链路静默瘫痪（发送方仍能拿到 ack，但消息永远不落库）。
 *
 * <p>判定规则遵循《12 RocketMQ 治理规范》「不可恢复的契约、校验或业务拒绝错误不得无限重试」：
 * <ul>
 *   <li>契约、校验与业务拒绝（HTTP 4xx，例如「不是好友」）不可恢复：记录原因并转入 DLQ，立即终止该条。</li>
 *   <li>瞬时故障（数据库、网络、超时等）可恢复：退避重试；达到上限后同样终止并转入 DLQ，避免永久阻塞。</li>
 * </ul>
 */
@Slf4j
public class RemotingMqConsumerFactory implements MqConsumerFactory {
  /** 单条瞬时故障消息的最大尝试次数；达到上限后终止该条并转 DLQ，保证队列必定继续前进。 */
  private static final int MAX_TRANSIENT_ATTEMPTS = 5;
  /** 终止消息时落日志的报文最大长度，便于按运维流程人工补偿。 */
  private static final int MAX_LOGGED_PAYLOAD = 2048;
  /** DLQ 主题后缀。 */
  private static final String DLQ_TOPIC_SUFFIX = "_DLQ";

  private final String namesrvAddr;
  private final MqProducer dlqProducer;
  /** 瞬时故障消息的进程内尝试计数；进程重启后重新计数，不影响「不可恢复失败立即终止」的保证。 */
  private final ConcurrentHashMap<String, AtomicInteger> transientAttempts = new ConcurrentHashMap<>();

  public RemotingMqConsumerFactory(String namesrvAddr) {
    this(namesrvAddr, null);
  }

  public RemotingMqConsumerFactory(String namesrvAddr, MqProducer dlqProducer) {
    this.namesrvAddr = namesrvAddr;
    this.dlqProducer = dlqProducer;
  }

  @Override
  public AutoCloseable createOrderedConsumer(String topic, String consumerGroup, MqMessageHandler handler)
      throws Exception {
    DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
    consumer.setNamesrvAddr(namesrvAddr);
    consumer.setConsumeFromWhere(initialConsumeFromWhere());
    consumer.subscribe(topic, "*");
    AtomicBoolean started = new AtomicBoolean(false);
    log.info("Creating ordered RocketMQ consumer, topic={}, consumerGroup={}, namesrvAddr={}", topic, consumerGroup, namesrvAddr);
    consumer.registerMessageListener((MessageListenerOrderly) (messages, context) ->
        consume(topic, consumerGroup, messages, context, handler));
    consumer.start();
    started.set(true);
    return () -> {
      if (started.get()) {
        log.info("Shutting down ordered RocketMQ consumer, topic={}, consumerGroup={}", topic, consumerGroup);
        consumer.shutdown();
      }
    };
  }

  static ConsumeFromWhere initialConsumeFromWhere() {
    return ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET;
  }

  /**
   * 处理一批有序消息。
   *
   * <p>返回 {@link ConsumeOrderlyStatus#SUCCESS} 才会推进位点；返回
   * {@link ConsumeOrderlyStatus#SUSPEND_CURRENT_QUEUE_A_MOMENT} 会挂起整个队列。
   * 因此**任何无法恢复的失败都必须就地终止并继续**，否则队列永久停摆。
   */
  ConsumeOrderlyStatus consume(String topic, String consumerGroup, List<MessageExt> messages,
      ConsumeOrderlyContext context, MqMessageHandler handler) {
    for (MessageExt message : messages) {
      String messageId = message.getMsgId();
      try {
        handler.handle(message.getBody());
        transientAttempts.remove(messageId);
      } catch (Exception ex) {
        if (isUnrecoverable(ex)) {
          terminate(topic, consumerGroup, message, ex, "unrecoverable business/contract rejection");
          continue;
        }
        int attempt = transientAttempts
            .computeIfAbsent(messageId, key -> new AtomicInteger())
            .incrementAndGet();
        if (attempt >= MAX_TRANSIENT_ATTEMPTS) {
          transientAttempts.remove(messageId);
          terminate(topic, consumerGroup, message, ex,
              "transient failure exceeded " + MAX_TRANSIENT_ATTEMPTS + " attempts");
          continue;
        }
        log.error(
            "Ordered RocketMQ consumer handling failed, retrying, topic={}, consumerGroup={}, msgId={}, attempt={}/{}, batchSize={}",
            topic,
            consumerGroup,
            messageId,
            attempt,
            MAX_TRANSIENT_ATTEMPTS,
            messages.size(),
            ex);
        context.setSuspendCurrentQueueTimeMillis(1000L);
        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
      }
    }
    return ConsumeOrderlyStatus.SUCCESS;
  }

  /**
   * 是否为不可恢复失败：**只有重试结果必然相同**的错误才判定为不可恢复。
   *
   * <p>判定刻意保持保守（宁可多试几次，也不要丢掉本该成功的消息）：
   * <ul>
   *   <li>计入不可恢复：400 参数/契约错误、401 未认证、403 业务拒绝（如「不是好友」）、422 语义校验失败，
   *       以及反序列化/契约异常——这些重试多少次结果都一样。</li>
   *   <li>**不计入**（仍按瞬时故障有限重试）：404（可能只是会话/成员数据尚未落库的时序窗口）、
   *       408/409/429（超时、并发冲突、限流，重试常常就能成功）以及所有 5xx。</li>
   * </ul>
   * 无论哪一类都不会无限重试：瞬时故障也有尝试次数上限，超限后同样终止并转 DLQ。
   */
  static boolean isUnrecoverable(Throwable ex) {
    for (Throwable current = ex; current != null; current = current.getCause()) {
      if (current instanceof ApiException apiException) {
        return isUnrecoverableStatus(apiException.getStatus());
      }
      if (current instanceof JsonProcessingException || current instanceof IllegalArgumentException) {
        return true;
      }
    }
    return false;
  }

  private static boolean isUnrecoverableStatus(int status) {
    return status == 400 || status == 401 || status == 403 || status == 422;
  }

  /** 受控终止一条无法恢复的消息：记录可追溯的失败上下文并转入 DLQ，随后推进位点。 */
  private void terminate(String topic, String consumerGroup, MessageExt message, Exception ex, String reason) {
    log.error(
        "Terminating ordered message so the queue can advance, reason={}, topic={}, consumerGroup={}, msgId={}, keys={}, payload={}",
        reason,
        topic,
        consumerGroup,
        message.getMsgId(),
        message.getKeys(),
        payloadOf(message),
        ex);
    if (dlqProducer == null) {
      return;
    }
    String dlqTopic = topic + DLQ_TOPIC_SUFFIX;
    try {
      String shardingKey = message.getKeys() == null || message.getKeys().isEmpty()
          ? message.getMsgId()
          : message.getKeys();
      dlqProducer.sendOrdered(dlqTopic, shardingKey, message.getMsgId(), message.getBody());
      log.error(
          "Moved unrecoverable message to DLQ, dlqTopic={}, msgId={}, reason={}",
          dlqTopic,
          message.getMsgId(),
          reason);
    } catch (Exception dlqException) {
      // DLQ 写入失败不能反过来阻塞队列，只记录以便人工按 payload 补偿。
      log.error(
          "Failed to move unrecoverable message to DLQ, dlqTopic={}, msgId={}",
          dlqTopic,
          message.getMsgId(),
          dlqException);
    }
  }

  private static String payloadOf(MessageExt message) {
    byte[] body = message.getBody();
    if (body == null || body.length == 0) {
      return "";
    }
    String payload = new String(body, StandardCharsets.UTF_8);
    if (payload.length() <= MAX_LOGGED_PAYLOAD) {
      return payload;
    }
    return payload.substring(0, MAX_LOGGED_PAYLOAD) + "...(truncated)";
  }
}
