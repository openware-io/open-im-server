package io.openware.im.admin.application.projection;

import io.openware.protocol.mq.event.MessageStoredEvent;
import io.openware.protocol.mq.event.UserStatusChangedEvent;

public interface AdminReadProjectionPort {
  void project(UserStatusChangedEvent event);

  void project(MessageStoredEvent event);
}
