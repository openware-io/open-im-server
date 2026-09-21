package com.gvchat.im.admin.application.projection;

import com.gvchat.protocol.mq.event.MessageStoredEvent;
import com.gvchat.protocol.mq.event.UserStatusChangedEvent;

public interface AdminReadProjectionPort {
  void project(UserStatusChangedEvent event);

  void project(MessageStoredEvent event);
}
