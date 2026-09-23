package io.openware.infrastructure.mq;

public interface MqConsumerFactory {
  AutoCloseable createOrderedConsumer(String topic, String consumerGroup, MqMessageHandler handler) throws Exception;
}
