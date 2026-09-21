package com.gvchat.im.admin.application.query;

import com.gvchat.common.dto.PageResult;
import com.gvchat.im.admin.integration.AdminReadClient;
import com.gvchat.im.user.api.admin.AdminAccountCancellationLogResponse;
import com.gvchat.im.user.api.admin.AdminAccountCancellationResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAccountCancellationApplicationService {
  private final AdminReadClient adminReadClient;

  public PageResult<AdminAccountCancellationResponse> list(int page, int pageSize, Long userId, String status,
      String keyword) {
    return adminReadClient.listAccountCancellations(page, pageSize, userId, status, keyword);
  }

  public List<AdminAccountCancellationLogResponse> listLogs(Long id) {
    return adminReadClient.listAccountCancellationLogs(id);
  }

  public PageResult<AdminAccountCancellationLogResponse> searchLogs(int page, int pageSize, Long cancellationId,
      Long userId, String action) {
    return adminReadClient.searchAccountCancellationLogs(page, pageSize, cancellationId, userId, action);
  }
}
