package com.gvchat.im.conversation.domain.group.port;

import java.util.List;

public interface ActiveUserPort {
  List<Long> findActiveUserIds(List<Long> userIds);
}
