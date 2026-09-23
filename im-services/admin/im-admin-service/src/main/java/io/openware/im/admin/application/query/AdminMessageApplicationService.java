package io.openware.im.admin.application.query;

import io.openware.im.admin.integration.AdminReadClient;
import io.openware.common.dto.AdminListMessagesDto;
import io.openware.common.dto.PageResult;
import io.openware.im.message.api.admin.AdminMessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminMessageApplicationService {
  private final AdminReadClient adminReadClient;

  public PageResult<AdminMessageResponse> listMessages(AdminListMessagesDto query) {
    return adminReadClient.listMessages(query);
  }
}
