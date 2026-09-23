package io.openware.im.admin.application.query;

import io.openware.common.dto.PageResult;
import io.openware.im.admin.integration.AdminReadClient;
import io.openware.im.user.api.admin.AdminAccountCancellationLogResponse;
import io.openware.im.user.api.admin.AdminAccountCancellationResponse;
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
