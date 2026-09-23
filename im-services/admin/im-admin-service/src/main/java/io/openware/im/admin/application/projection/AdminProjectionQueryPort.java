package io.openware.im.admin.application.projection;

import io.openware.common.dto.AdminListMessagesDto;
import io.openware.common.dto.AdminListUsersDto;
import io.openware.common.dto.PageResult;
import io.openware.im.message.api.admin.AdminMessageResponse;
import io.openware.im.user.api.admin.AdminUserResponse;

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
