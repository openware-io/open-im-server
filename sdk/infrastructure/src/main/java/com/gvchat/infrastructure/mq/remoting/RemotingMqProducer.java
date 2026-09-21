package com.gvchat.infrastructure.mq.remoting;

import com.gvchat.infrastructure.mq.MqProducer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;

@Slf4j
public class RemotingMqProducer implements MqProducer, AutoCloseable {
  private final DefaultMQProducer producer;

  public RemotingMqProducer(DefaultMQProducer producer) {
    this.producer = producer;
  }

  @Override
  public void sendOrdered(String topic, String shardingKey, String key, byte[] body) throws Exception {
    Message message = new Message(topic, body);
    message.setKeys(key);
    message.putUserProperty("shardingKey", shardingKey);
    log.debug(
        "Sending ordered RocketMQ message, topic={}, shardingKey={}, key={}, bodySize={}",
        topic,
        shardingKey,
        key,
        body == null ? 0 : body.length);
    producer.send(message, new StableQueueSelector(), shardingKey);
  }

  @Override
  public boolean sendOrderedDelayed(String topic, String shardingKey, String key, byte[] body, int delaySeconds)
      throws Exception {
    int level = delayTimeLevel(delaySeconds);
    if (level <= 0) {
      return false;
    }
    Message message = new Message(topic, body);
    message.setKeys(key);
    message.putUserProperty("shardingKey", shardingKey);
    message.setDelayTimeLevel(level);
    log.info(
        "Sending delayed RocketMQ message, topic={}, shardingKey={}, key={}, delaySeconds={}, delayTimeLevel={}",
        topic, shardingKey, key, delaySeconds, level);
    producer.send(message, new StableQueueSelector(), shardingKey);
    return true;
  }

  /**
   * RocketMQ 内置延迟级别：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h。
   * 返回 >= [delaySeconds] 的最近级别；超过 2h 返回 -1（不支持）。
   */
  private static int delayTimeLevel(int delaySeconds) {
    if (delaySeconds <= 1) return 1;
    if (delaySeconds <= 5) return 2;
    if (delaySeconds <= 10) return 3;
    if (delaySeconds <= 30) return 4;
    if (delaySeconds <= 60) return 5;
    if (delaySeconds <= 120) return 6;
    if (delaySeconds <= 180) return 7;
    if (delaySeconds <= 240) return 8;
    if (delaySeconds <= 300) return 9;
    if (delaySeconds <= 360) return 10;
    if (delaySeconds <= 420) return 11;
    if (delaySeconds <= 480) return 12;
    if (delaySeconds <= 540) return 13;
    if (delaySeconds <= 600) return 14;
    if (delaySeconds <= 1200) return 15;
    if (delaySeconds <= 1800) return 16;
    if (delaySeconds <= 3600) return 17;
    if (delaySeconds <= 7200) return 18;
    return -1;
  }

  @Override
  public void close() {
    log.info("Shutting down remoting RocketMQ producer.");
    producer.shutdown();
  }

  private static final class StableQueueSelector implements MessageQueueSelector {
    @Override
    public MessageQueue select(List<MessageQueue> queues, Message msg, Object arg) {
      if (queues.isEmpty()) {
        log.error("No available RocketMQ queues for topic {}", msg.getTopic());
        throw new IllegalStateException("No available RocketMQ queues for topic " + msg.getTopic());
      }
      String shardingKey = arg == null ? "" : String.valueOf(arg);
      int index = Math.floorMod(shardingKey.hashCode(), queues.size());
      return queues.get(index);
    }
  }
}
