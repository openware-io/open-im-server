package com.gvchat.protocol.mq.group;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ImMqGroupsTest {
  @Test
  void shouldDefineProducerGroups() {
    assertEquals("im-message-service-producer", ImMqProducerGroups.MESSAGE_SERVICE);
    assertEquals("im-access-ws-producer", ImMqProducerGroups.ACCESS_WS);
  }

  @Test
  void shouldDefineConsumerGroups() {
    assertEquals("im-message-service-write-command-consumer", ImMqConsumerGroups.MESSAGE_SERVICE_WRITE_COMMAND);
    assertEquals("im-message-service-read-command-consumer", ImMqConsumerGroups.MESSAGE_SERVICE_READ_COMMAND);
    assertEquals("im-message-service-recall-command-consumer", ImMqConsumerGroups.MESSAGE_SERVICE_RECALL_COMMAND);
    assertEquals("im-access-ws-delivery-consumer", ImMqConsumerGroups.ACCESS_WS_DELIVERY);
    assertEquals("im-admin-service-user-status-projection-consumer", ImMqConsumerGroups.ADMIN_PROJECTION_USER_STATUS);
    assertEquals("im-admin-service-message-projection-consumer", ImMqConsumerGroups.ADMIN_PROJECTION_MESSAGE);
    assertEquals("platform-identity-service-user-profile-changed-consumer",
        ImMqConsumerGroups.IDENTITY_SERVICE_USER_PROFILE_CHANGED);
  }
}
