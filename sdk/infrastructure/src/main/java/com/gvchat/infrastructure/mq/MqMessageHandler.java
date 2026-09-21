package com.gvchat.infrastructure.mq;

@FunctionalInterface
public interface MqMessageHandler {
  void handle(byte[] body) throws Exception;
}
