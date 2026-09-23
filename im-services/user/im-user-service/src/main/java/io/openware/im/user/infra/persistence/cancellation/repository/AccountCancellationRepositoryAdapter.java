package io.openware.im.user.infra.persistence.cancellation.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.cancellation.model.AccountCancellation;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStatus;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationRepository;
import io.openware.im.user.infra.persistence.cancellation.converter.AccountCancellationPersistenceConverter;
import io.openware.im.user.infra.persistence.cancellation.mapper.AccountCancellationMapper;
import io.openware.im.user.infra.persistence.cancellation.po.AccountCancellationPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AccountCancellationRepositoryAdapter implements AccountCancellationRepository {
  private final AccountCancellationMapper mapper;

  @Override
  public Optional<AccountCancellation> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(AccountCancellationPersistenceConverter::toDomain);
  }

  @Override
  public Optional<AccountCancellation> findActiveByUserId(Long userId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<AccountCancellationPo>lambdaQuery()
        .eq(AccountCancellationPo::getUserId, userId)
        .in(AccountCancellationPo::getStatus, AccountCancellationStatus.PENDING.name().toLowerCase(),
            AccountCancellationStatus.PROCESSING.name().toLowerCase())
        .orderByDesc(AccountCancellationPo::getId)
        .last("LIMIT 1")))
        .map(AccountCancellationPersistenceConverter::toDomain);
  }

  @Override
  public AccountCancellation save(AccountCancellation application) {
    AccountCancellationPo po = AccountCancellationPersistenceConverter.toPo(application);
    if (po.getId() == null) {
      mapper.insert(po);
      application.restore(po.getId(), application.getUserId(), application.getUsername(), application.getNickname(),
          application.getPhone(), application.getEmail(), application.getStatus(), application.getStatusTokenHash(),
          application.getSource(), application.getCompletedSteps(), application.getRequestedIp(),
          application.getRequestedAt(), application.getProcessingAt(), application.getCompletedAt(),
          application.getFailureReason(), application.getRowVersion(), application.getCreatedBy(),
          application.getCreatedAt(), application.getUpdatedBy(), application.getUpdatedAt());
    } else {
      mapper.updateById(po);
    }
    return application;
  }

  @Override
  public List<AccountCancellation> findPending(int limit) {
    return mapper.selectList(Wrappers.<AccountCancellationPo>lambdaQuery()
            .eq(AccountCancellationPo::getStatus, AccountCancellationStatus.PENDING.name().toLowerCase())
            .orderByAsc(AccountCancellationPo::getId)
            .last("LIMIT " + limit))
        .stream()
        .map(AccountCancellationPersistenceConverter::toDomain)
        .toList();
  }

  @Override
  public PageResult<AccountCancellation> searchForAdmin(Long userId, AccountCancellationStatus status, String keyword,
      int page, int pageSize) {
    LambdaQueryWrapper<AccountCancellationPo> query = Wrappers.<AccountCancellationPo>lambdaQuery()
        .eq(userId != null, AccountCancellationPo::getUserId, userId)
        .eq(status != null, AccountCancellationPo::getStatus, status == null ? null : status.name().toLowerCase());
    if (keyword != null && !keyword.isBlank()) {
      query.and(wrapper -> wrapper.like(AccountCancellationPo::getUsername, keyword)
          .or().like(AccountCancellationPo::getNickname, keyword));
    }
    Page<AccountCancellationPo> result = mapper.selectPage(new Page<>(page, pageSize),
        query.orderByDesc(AccountCancellationPo::getId));
    return PageResult.<AccountCancellation>builder()
        .items(result.getRecords().stream().map(AccountCancellationPersistenceConverter::toDomain).toList())
        .total(result.getTotal())
        .page((int) result.getCurrent())
        .pageSize((int) result.getSize())
        .build();
  }
}
