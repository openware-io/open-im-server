package io.openware.im.user.infra.persistence.account.repository;

import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.model.UserStatusOperation;
import io.openware.im.user.domain.account.repository.UserStatusOperationRepository;
import io.openware.im.user.infra.persistence.account.mapper.UserStatusOperationMapper;
import io.openware.im.user.infra.persistence.account.po.UserStatusOperationPo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Repository
@RequiredArgsConstructor
public class UserStatusOperationRepositoryAdapter implements UserStatusOperationRepository {
  private final UserStatusOperationMapper mapper;

  @Override
  public Optional<UserStatusOperation> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<UserStatusOperationPo>()
        .eq(UserStatusOperationPo::getIdempotencyKey, idempotencyKey)))
        .map(value -> new UserStatusOperation(value.getRequestVersion(), value.getUserId(),
            UserAccountStatus.valueOf(value.getPreviousStatus().toUpperCase()),
            UserAccountStatus.valueOf(value.getCurrentStatus().toUpperCase()), value.getStatusVersion(),
            value.getIdempotencyKey(), value.getCorrelationId(), value.getCreatedAt()));
  }

  @Override
  public void save(UserStatusOperation operation, long expectedStatusVersion, Long operatorId, String reason) {
    UserStatusOperationPo operationPo = new UserStatusOperationPo();
    operationPo.setIdempotencyKey(operation.idempotencyKey());
    operationPo.setUserId(operation.userId());
    operationPo.setExpectedVersion(expectedStatusVersion);
    operationPo.setPreviousStatus(operation.previousStatus().name().toLowerCase());
    operationPo.setCurrentStatus(operation.currentStatus().name().toLowerCase());
    operationPo.setStatusVersion(operation.statusVersion());
    operationPo.setOperatorId(operatorId);
    operationPo.setReason(reason);
    operationPo.setCorrelationId(operation.correlationId());
    operationPo.setRequestVersion(operation.requestVersion());
    // 后台操作审计：操作人即 operatorId（缺省 0 = 系统）；其余审计列的默认值由 DDL 提供。
    operationPo.setCreatedBy(operatorId == null ? 0L : operatorId);
    operationPo.setCreatedAt(operation.occurredAt());
    mapper.insert(operationPo);
  }
}
