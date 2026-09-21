package com.gvchat.protocol.mq.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConversationIdsTest {
  @Test
  void shouldNormalizePrivateConversationOrder() {
    assertEquals("conv:private:7:9", ConversationIds.privateConversation(9, 7));
  }

  @Test
  void shouldBuildGroupConversationId() {
    assertEquals("conv:group:12", ConversationIds.groupConversation(12));
  }
}
