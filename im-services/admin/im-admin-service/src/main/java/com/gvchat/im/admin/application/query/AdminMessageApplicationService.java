package com.gvchat.im.admin.application.query;

import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.common.dto.AdminListMessagesDto;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.message.api.admin.AdminMessageResponse;
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
