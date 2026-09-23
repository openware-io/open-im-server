package io.openware.im.user.domain.cancellation.repository;

import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.cancellation.model.AccountCancellation;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStatus;
import java.util.List;
import java.util.Optional;

public interface AccountCancellationRepository {
  Optional<AccountCancellation> findById(Long id);

  /** 查询该用户处于 pending/processing 的申请（用于防止重复提交）。 */
  Optional<AccountCancellation> findActiveByUserId(Long userId);

  AccountCancellation save(AccountCancellation application);

  /** 按 id 升序查询待处理申请，限量。 */
  List<AccountCancellation> findPending(int limit);

  PageResult<AccountCancellation> searchForAdmin(Long userId, AccountCancellationStatus status, String keyword, int page,
      int pageSize);
}
