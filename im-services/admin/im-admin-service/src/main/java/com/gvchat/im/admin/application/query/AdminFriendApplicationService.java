package com.gvchat.im.admin.application.query;

import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.common.dto.AdminListFriendsDto;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.api.admin.AdminFriendResponse;
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
