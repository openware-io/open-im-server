package io.openware.im.user.domain.cancellation.repository;

import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.cancellation.model.AccountCancellationAction;
import io.openware.im.user.domain.cancellation.model.AccountCancellationLog;
import java.util.List;

public interface AccountCancellationLogRepository {
  void save(AccountCancellationLog log);

  List<AccountCancellationLog> findByCancellationId(Long cancellationId);

  PageResult<AccountCancellationLog> searchForAdmin(Long cancellationId, Long userId, AccountCancellationAction action,
      int page, int pageSize);
}
