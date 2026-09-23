package io.openware.im.user.infra.persistence.cancellation.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.cancellation.model.AccountCancellationAction;
import io.openware.im.user.domain.cancellation.model.AccountCancellationLog;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationLogRepository;
import io.openware.im.user.infra.persistence.cancellation.converter.AccountCancellationLogPersistenceConverter;
import io.openware.im.user.infra.persistence.cancellation.mapper.AccountCancellationLogMapper;
import io.openware.im.user.infra.persistence.cancellation.po.AccountCancellationLogPo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AccountCancellationLogRepositoryAdapter implements AccountCancellationLogRepository {
  private final AccountCancellationLogMapper mapper;

  @Override
  public void save(AccountCancellationLog log) {
    mapper.insert(AccountCancellationLogPersistenceConverter.toPo(log));
  }

  @Override
  public List<AccountCancellationLog> findByCancellationId(Long cancellationId) {
    return mapper.selectList(Wrappers.<AccountCancellationLogPo>lambdaQuery()
            .eq(AccountCancellationLogPo::getCancellationId, cancellationId)
            .orderByAsc(AccountCancellationLogPo::getId))
        .stream()
        .map(AccountCancellationLogPersistenceConverter::toDomain)
        .toList();
  }

  @Override
  public PageResult<AccountCancellationLog> searchForAdmin(Long cancellationId, Long userId,
      AccountCancellationAction action, int page, int pageSize) {
    LambdaQueryWrapper<AccountCancellationLogPo> query = Wrappers.<AccountCancellationLogPo>lambdaQuery()
        .eq(cancellationId != null, AccountCancellationLogPo::getCancellationId, cancellationId)
        .eq(userId != null, AccountCancellationLogPo::getUserId, userId)
        .eq(action != null, AccountCancellationLogPo::getAction, action == null ? null : action.name().toLowerCase());
    Page<AccountCancellationLogPo> result = mapper.selectPage(new Page<>(page, pageSize),
        query.orderByDesc(AccountCancellationLogPo::getId));
    return PageResult.<AccountCancellationLog>builder()
        .items(result.getRecords().stream().map(AccountCancellationLogPersistenceConverter::toDomain).toList())
        .total(result.getTotal())
        .page((int) result.getCurrent())
        .pageSize((int) result.getSize())
        .build();
  }
}
