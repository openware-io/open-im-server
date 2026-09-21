package com.gvchat.im.user.infra.persistence.account.repository;

import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.model.UserStatusOperation;
import com.gvchat.im.user.domain.account.repository.UserStatusOperationRepository;
import com.gvchat.im.user.infra.persistence.account.mapper.UserStatusOperationMapper;
import com.gvchat.im.user.infra.persistence.account.po.UserStatusOperationPo;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserStatusOperationRepositoryAdapter implements UserStatusOperationRepository {
  private final UserStatusOperationMapper mapper;

  @Override
  public Optional<UserStatusOperation> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserStatusOperationPo>()
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
    operationPo.setCreatedAt(operation.occurredAt());
    mapper.insert(operationPo);
  }
}
