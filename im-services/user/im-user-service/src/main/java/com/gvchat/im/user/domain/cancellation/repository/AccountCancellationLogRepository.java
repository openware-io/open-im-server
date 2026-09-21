package com.gvchat.im.user.domain.cancellation.repository;

import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellationAction;
import com.gvchat.im.user.domain.cancellation.model.AccountCancellationLog;
import java.util.List;

public interface AccountCancellationLogRepository {
  void save(AccountCancellationLog log);

  List<AccountCancellationLog> findByCancellationId(Long cancellationId);

  PageResult<AccountCancellationLog> searchForAdmin(Long cancellationId, Long userId, AccountCancellationAction action,
      int page, int pageSize);
}
