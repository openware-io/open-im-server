package io.openware.im.admin.application.query;

import io.openware.im.admin.integration.AdminReadClient;
import io.openware.common.dto.AdminListFriendsDto;
import io.openware.common.dto.PageResult;
import io.openware.im.user.api.admin.AdminFriendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminFriendApplicationService {
  private final AdminReadClient adminReadClient;

  public PageResult<AdminFriendResponse> listFriends(AdminListFriendsDto query) {
    return adminReadClient.listFriends(query);
  }
}
