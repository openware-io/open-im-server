package io.openware.im.conversation.domain.group.port;

import java.util.List;
import java.util.Map;

public interface UserProfilePort {
  Map<Long, UserProfile> findByUserIds(List<Long> userIds);

  record UserProfile(String username, String nickname, String avatar) {
  }
}
