package io.openware.platform.customer.domain.point.model;

import java.time.LocalDateTime;

/**
 * 积分流水（账本）条目，只追加。
 *
 * <p>积分账本自证币种（{@code currencyCode} 取当时租户币种快照），不依赖回查账户：
 * 积分本身不是货币，但该笔变动的账面口径属于当时租户币种，报表与审计按币种归集。
 *
 * <p><b>字段集只来自真实 DDL 列</b>（{@code cst_point_ledger}）。{@code CstPointLedgerPo} 上
 * <b>没有</b> {@code @TableField(exist = false)} 的展示字段——这与钱包账户相反，是逐一核对过的：
 * 对不存在的列生成引用会在运行时抛 {@code can not find lambda cache for this property}，
 * 而 {@code test-compile} 看不出来。
 *
 * <p>{@code createdBy} / {@code updatedBy} / {@code updatedAt} 是审计列，不在此承载（适配器填充）。
 * {@code createdAt} 例外并<b>刻意保留</b>：它是 NOT NULL 的真实列，且 {@code GET /me/points} 的
 * 流水行一直把它作为响应字段暴露（见 {@code PointLedgerRow}），删掉会改成契约。
 *
 * @param id               流水标识
 * @param tenantId         租户
 * @param storeId          本笔积分业务发生门店；积分账户本身不分店，{@code null} = 非门店场景
 * @param accountId        所属积分账户
 * @param entryType        类型：ADJUST / REDEEM / EARN / REVERSE
 * @param points           变动积分（个数；正数增加、负数冲减，方向由 entryType 共同表达）
 * @param currencyCode     币种快照（该笔变动所依据的租户币种口径）
 * @param balanceAfter     变动后可用积分
 * @param businessType     业务类型（如 ORDER）；可为空
 * @param businessId       业务标识（如订单号）；可为空
 * @param idempotencyKey   幂等键；唯一键 {@code uk_cst_point_ledger_idem} 兜底
 * @param ruleSnapshotJson EARN 的规则快照（倍率/有效期/业态）；可为空
 * @param expiresAt        EARN 的失效时间；{@code null} = 不失效
 * @param occurredAt       业务发生时间
 * @param createdAt        落库时间
 */
public record PointLedgerEntry(
    Long id,
    Long tenantId,
    Long storeId,
    Long accountId,
    String entryType,
    Long points,
    String currencyCode,
    Long balanceAfter,
    String businessType,
    Long businessId,
    String idempotencyKey,
    String ruleSnapshotJson,
    LocalDateTime expiresAt,
    LocalDateTime occurredAt,
    LocalDateTime createdAt) {

  /** 人工调整（points 可正可负）。 */
  public static final String TYPE_ADJUST = "ADJUST";
  /** 抵扣（组合收款，余额扣减）。 */
  public static final String TYPE_REDEEM = "REDEEM";
  /** 获得（支付确认后按规则计算）。 */
  public static final String TYPE_EARN = "EARN";
  /** 反向流水（组合收款失败补偿归还）。 */
  public static final String TYPE_REVERSE = "REVERSE";

  /** 变动积分（为空视为 0）。 */
  public long pointsOrZero() {
    return points == null ? 0L : points;
  }

  /** 变动后余额（为空视为 0）。 */
  public long balanceAfterOrZero() {
    return balanceAfter == null ? 0L : balanceAfter;
  }
}
