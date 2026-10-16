package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import java.time.LocalDateTime;

/**
 * 积分流水行（<b>数量口径</b>）：{@code GET /me/points} 与
 * {@code GET /business/members/{id}/points} 的流水记录。
 *
 * <p>积分就是<b>数量</b>（1 积分 = 1 个，1:1 不换算）：本视图<b>不含币种、不含金额、不含 tokenAmount</b>。
 * 积分既不是货币（不显示货币符号/币种），也不是储值代币（§9 的 wallet_ratio 代币换算不适用于积分）。
 * 数据库中的 {@code cst_point_ledger.currency_code} 只是该笔积分变动所依据的租户币种快照（报表/审计用），
 * 不进入积分响应。
 *
 * <p><b>字段名照搬原持久化投影</b>：投影源从 {@code CstPointLedgerPo} 换成领域条目
 * {@link PointLedgerEntry}，但输出键一个没改（{@code id / entryType / points / balanceAfter /
 * businessType / businessId / occurredAt / createdAt}）。{@code createdAt} 是真实列，因此有值。
 */
public record PointLedgerRow(
    Long id,
    String entryType,
    Long points,
    Long balanceAfter,
    String businessType,
    Long businessId,
    LocalDateTime occurredAt,
    LocalDateTime createdAt) {

  /** 把领域流水投影为数量口径行（剥离 currencyCode / idempotencyKey / ruleSnapshotJson 等内部字段）。 */
  public static PointLedgerRow from(PointLedgerEntry entry) {
    if (entry == null) {
      return null;
    }
    return new PointLedgerRow(entry.id(), entry.entryType(), entry.points(), entry.balanceAfter(),
        entry.businessType(), entry.businessId(), entry.occurredAt(), entry.createdAt());
  }
}
