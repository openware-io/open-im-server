package com.gvchat.infrastructure.mq.json;

import com.fasterxml.jackson.databind.ObjectMapper;

public class MqJsonCodec {
  private final ObjectMapper objectMapper;

  public MqJsonCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public byte[] toBytes(Object payload) throws Exception {
    return objectMapper.writeValueAsBytes(payload);
  }

  public <T> T fromBytes(byte[] body, Class<T> type) throws Exception {
    return objectMapper.readValue(body, type);
  }
}
