package com.gvchat.im.admin.application.projection;

import com.gvchat.common.dto.AdminListMessagesDto;
import com.gvchat.common.dto.AdminListUsersDto;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.message.api.admin.AdminMessageResponse;
import com.gvchat.im.user.api.admin.AdminUserResponse;

public interface AdminProjectionQueryPort {
  PageResult<AdminUserResponse> listUsers(AdminListUsersDto query);

  PageResult<AdminMessageResponse> listMessages(AdminListMessagesDto query);

  long userCount();

  long todayNewUsers();

  long newUsers(int days);

  long messageCount();

  long newMessages(int days);

  long groupCount();

  long newGroups(int days);
}
