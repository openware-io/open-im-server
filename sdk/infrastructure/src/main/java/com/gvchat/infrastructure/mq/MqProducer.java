package com.gvchat.infrastructure.mq;

public interface MqProducer {
  void sendOrdered(String topic, String shardingKey, String key, byte[] body) throws Exception;

  /**
   * 发送延迟消息：RocketMQ 到期后才投递给消费者。
   *
   * @param delaySeconds 延迟秒数；超过 RocketMQ 支持的延迟级别上限（默认 2h）时
   *                     返回 false（调用方应改用其他兜底机制，如周期扫描）。
   */
  boolean sendOrderedDelayed(String topic, String shardingKey, String key, byte[] body, int delaySeconds)
      throws Exception;
}
