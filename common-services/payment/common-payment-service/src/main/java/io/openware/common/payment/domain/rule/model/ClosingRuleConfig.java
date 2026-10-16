package io.openware.common.payment.domain.rule.model;

/**
 * 日结规则配置（领域模型）。
 *
 * <p>只按门店定位（{@code tenant_id + store_id}）：日结时间点天然是门店级事实，
 * 没有业态或租户默认层级。
 *
 * <p>审计字段不在此承载：由持久化适配器填充。{@code version} 保留：乐观锁并发语义。
 *
 * @param id             配置标识
 * @param tenantId       租户
 * @param storeId        门店
 * @param closingMinute  日结时间点（当日第几分钟，0–1439）
 * @param version        版本号（乐观锁）
 * @param idempotencyKey 写入幂等键
 * @param status         状态：ACTIVE / DISABLED
 */
public record ClosingRuleConfig(
    Long id,
    Long tenantId,
    Long storeId,
    Integer closingMinute,
    Integer version,
    String idempotencyKey,
    String status) {

  /** 配置生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 一天内的合法分钟数上界（含）。 */
  public static final int MAX_MINUTE_OF_DAY = 1439;

  /** 日结时间点是否在合法范围内（0–1439）。 */
  public static boolean validClosingMinute(Integer closingMinute) {
    return closingMinute != null && closingMinute >= 0 && closingMinute <= MAX_MINUTE_OF_DAY;
  }
}
