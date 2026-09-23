package io.openware.im.user.application.cancellation;

import io.openware.common.dto.PageResult;
import io.openware.im.user.api.admin.AdminAccountCancellationLogResponse;
import io.openware.im.user.api.admin.AdminAccountCancellationResponse;
import io.openware.im.user.domain.cancellation.model.AccountCancellation;
import io.openware.im.user.domain.cancellation.model.AccountCancellationAction;
import io.openware.im.user.domain.cancellation.model.AccountCancellationLog;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStatus;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationLogRepository;
import io.openware.im.user.domain.cancellation.repository.AccountCancellationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号注销申请后台查询服务：供内部管理接口回填列表与审计日志。 */
@Service
@RequiredArgsConstructor
public class AccountCancellationAdminQueryService {
  private final AccountCancellationRepository accountCancellationRepository;
  private final AccountCancellationLogRepository logRepository;

  @Transactional(readOnly = true)
  public PageResult<AdminAccountCancellationResponse> list(Long userId, String status, String keyword, int page,
      int pageSize) {
    AccountCancellationStatus parsed = status == null || status.isBlank()
        ? null : AccountCancellationStatus.fromValue(status);
    PageResult<AccountCancellation> source =
        accountCancellationRepository.searchForAdmin(userId, parsed, keyword, page, pageSize);
    return PageResult.<AdminAccountCancellationResponse>builder()
        .items(source.getItems().stream().map(this::toResponse).toList())
        .total(source.getTotal())
        .page(source.getPage())
        .pageSize(source.getPageSize())
        .build();
  }

  @Transactional(readOnly = true)
  public List<AdminAccountCancellationLogResponse> listLogs(Long cancellationId) {
    return logRepository.findByCancellationId(cancellationId).stream().map(this::toLogResponse).toList();
  }

  @Transactional(readOnly = true)
  public PageResult<AdminAccountCancellationLogResponse> searchLogs(Long cancellationId, Long userId, String action,
      int page, int pageSize) {
    AccountCancellationAction parsed = action == null || action.isBlank()
        ? null : AccountCancellationAction.fromValue(action);
    PageResult<AccountCancellationLog> source =
        logRepository.searchForAdmin(cancellationId, userId, parsed, page, pageSize);
    return PageResult.<AdminAccountCancellationLogResponse>builder()
        .items(source.getItems().stream().map(this::toLogResponse).toList())
        .total(source.getTotal())
        .page(source.getPage())
        .pageSize(source.getPageSize())
        .build();
  }

  private AdminAccountCancellationResponse toResponse(AccountCancellation application) {
    return new AdminAccountCancellationResponse(
        application.getId(), application.getUserId(), application.getUsername(), application.getNickname(),
        application.getPhone(), application.getEmail(), application.getStatus().name(), application.getSource(),
        application.getCompletedSteps().stream()
            .map(step -> step.code()).sorted().toList(),
        application.getRequestedIp(), application.getRequestedAt(), application.getProcessingAt(),
        application.getCompletedAt(), application.getFailureReason());
  }

  private AdminAccountCancellationLogResponse toLogResponse(AccountCancellationLog log) {
    return new AdminAccountCancellationLogResponse(
        log.getId(), log.getCancellationId(), log.getUserId(),
        log.getAction() == null ? null : log.getAction().name(),
        log.getDetail(), log.getOperatorType(), log.getOperatorId(), log.getIp(), log.getOccurredAt());
  }
}
