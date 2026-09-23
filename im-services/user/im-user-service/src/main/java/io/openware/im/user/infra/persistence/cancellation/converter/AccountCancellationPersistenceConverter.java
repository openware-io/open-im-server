package io.openware.im.user.infra.persistence.cancellation.converter;

import io.openware.im.user.domain.cancellation.model.AccountCancellation;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStatus;
import io.openware.im.user.domain.cancellation.model.AccountCancellationStep;
import io.openware.im.user.infra.persistence.cancellation.po.AccountCancellationPo;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 账号注销申请 PO 与领域模型互转；已完成步骤以逗号分隔编码存储。 */
public final class AccountCancellationPersistenceConverter {
  private AccountCancellationPersistenceConverter() {
  }

  public static AccountCancellation toDomain(AccountCancellationPo po) {
    AccountCancellation application = new AccountCancellation();
    application.restore(
        po.getId(), po.getUserId(), po.getUsername(), po.getNickname(), po.getPhone(), po.getEmail(),
        AccountCancellationStatus.fromValue(po.getStatus()), po.getStatusTokenHash(), po.getSource(),
        parseSteps(po.getCompletedSteps()), po.getRequestedIp(), po.getRequestedAt(), po.getProcessingAt(),
        po.getCompletedAt(), po.getFailedReason(), po.getRowVersion() == null ? 1L : po.getRowVersion(),
        po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt());
    return application;
  }

  public static AccountCancellationPo toPo(AccountCancellation application) {
    AccountCancellationPo po = new AccountCancellationPo();
    po.setId(application.getId());
    po.setUserId(application.getUserId());
    po.setUsername(application.getUsername());
    po.setNickname(application.getNickname());
    po.setPhone(application.getPhone());
    po.setEmail(application.getEmail());
    po.setStatus(application.getStatus().name().toLowerCase());
    po.setStatusTokenHash(application.getStatusTokenHash());
    po.setSource(application.getSource());
    po.setCompletedSteps(formatSteps(application.getCompletedSteps()));
    po.setRequestedIp(application.getRequestedIp());
    po.setRequestedAt(application.getRequestedAt());
    po.setProcessingAt(application.getProcessingAt());
    po.setCompletedAt(application.getCompletedAt());
    po.setFailedReason(application.getFailureReason());
    po.setRowVersion(application.getRowVersion());
    po.setCreatedBy(application.getCreatedBy());
    po.setCreatedAt(application.getCreatedAt());
    po.setUpdatedBy(application.getUpdatedBy());
    po.setUpdatedAt(application.getUpdatedAt());
    return po;
  }

  private static Set<AccountCancellationStep> parseSteps(String csv) {
    if (csv == null || csv.isBlank()) {
      return EnumSet.noneOf(AccountCancellationStep.class);
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(AccountCancellationStep::fromCode)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(AccountCancellationStep.class)));
  }

  private static String formatSteps(Set<AccountCancellationStep> steps) {
    if (steps == null || steps.isEmpty()) {
      return null;
    }
    return steps.stream().map(AccountCancellationStep::code).collect(Collectors.joining(","));
  }
}
