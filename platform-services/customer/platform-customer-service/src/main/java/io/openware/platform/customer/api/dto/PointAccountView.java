package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.domain.point.model.PointAccount;
import java.time.LocalDateTime;

/**
 * 积分账户响应的对外视图。
 *
 * <p><b>字段集是刻意"照搬"的</b>：本接口此前直接返回持久化对象 {@code CstPointAccountPo}
 * （{@code GET /business/members/me} 内嵌、{@code POST /business/members/{id}/points/adjust} 响应体），
 * 所以 tenantId / programId / createdBy / createdAt / updatedBy / updatedAt 一直是响应的一部分。
 * 把它们删掉是一次 API 破坏（B 端与 C 端可能已在读），因此保留字段名；
 * 按 standards/10 D2 它们本不该出现在契约里，收紧需要单独一次带版本说明的变更。
 *
 * <p>审计时间戳（createdAt / updatedAt）在重构后为 {@code null}：领域模型按约定不承载审计字段，
 * 适配器只回填主键与版本。字段仍在，形状不变，但值不再有——这一点必须记在交接文档里，
 * 收紧字段集与恢复审计值都要单独一次变更（与钱包视图同一处境）。
 */
public record PointAccountView(
    Long id,
    Long tenantId,
    Long customerId,
    Long programId,
    Long availablePoints,
    Long frozenPoints,
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

  /** 由领域模型构造；审计字段留空（领域不承载，适配器不回填）。 */
  public static PointAccountView from(PointAccount account) {
    if (account == null) {
      return null;
    }
    return new PointAccountView(account.id(), account.tenantId(), account.customerId(),
        account.programId(), account.availablePoints(), account.frozenPoints(), account.version(),
        null, null, null, null);
  }
}
