package io.openware.platform.marketing.domain.consent.model;

import java.time.LocalDateTime;

/**
 * 营销同意记录（领域模型）：某账号在某渠道对某类同意的授权状态。
 *
 * <p>{@code occurredAt} 保留：它是**同意发生的业务时间**（同意行为的时刻），不同于 createdAt 这类记账时间。
 * {@code version} 保留：它是并发语义，每次状态迁移自增。
 * 其余审计字段（createdBy/At、updatedBy/At）不在此承载，由持久化适配器填充。
 *
 * @param id            记录标识
 * @param accountId     账号
 * @param tenantId      归属租户，可为空
 * @param consentType   同意类型：MARKETING / 履约通知 等
 * @param channel       渠道：APP / 短信 等
 * @param policyVersion 政策版本
 * @param granted       是否已授权
 * @param source        来源：BUSINESS / 其它
 * @param occurredAt    本次同意发生的业务时间
 * @param withdrawnAt   撤回时间；未撤回为空
 * @param version       版本号，每次状态迁移自增
 */
public record MktConsent(
    Long id,
    Long accountId,
    Long tenantId,
    String consentType,
    String channel,
    String policyVersion,
    boolean granted,
    String source,
    LocalDateTime occurredAt,
    LocalDateTime withdrawnAt,
    Integer version) {

  /** 默认同意类型。 */
  public static final String TYPE_MARKETING = "MARKETING";

  /** 默认渠道。 */
  public static final String CHANNEL_APP = "APP";

  /** 默认来源。 */
  public static final String SOURCE_BUSINESS = "BUSINESS";

  /** 默认政策版本。 */
  public static final String POLICY_VERSION_DEFAULT = "1.0";

  /**
   * 建立一条新的同意记录：类型／渠道／政策版本／来源各有既定回退口径，由本工厂统一给定。
   *
   * <p>回退口径收在这里而不是散在调用方的三元表达式里：它们是这条记录的构造规则，不是可配置项。
   */
  public static MktConsent record(Long accountId, Long tenantId, String consentType, String channel,
                                  String policyVersion, String source, boolean granted,
                                  LocalDateTime occurredAt) {
    return new MktConsent(null, accountId, tenantId,
        blankTo(consentType, TYPE_MARKETING), blankTo(channel, CHANNEL_APP),
        blankTo(policyVersion, POLICY_VERSION_DEFAULT), granted, blankTo(source, SOURCE_BUSINESS),
        occurredAt, granted ? null : occurredAt, 0);
  }

  /**
   * 变更授权状态（授予／撤回）：返回新实例，版本自增。
   *
   * <p>撤回时间与状态是同一个事实的两面，因此由本方法一并维护，避免调用方各自记得清理 {@code withdrawnAt}
   * 而留下"已授予但仍有撤回时间"的不一致记录。
   */
  public MktConsent changeGrant(boolean newGranted, String newPolicyVersion, LocalDateTime moment) {
    return new MktConsent(id, accountId, tenantId, consentType, channel,
        blankTo(newPolicyVersion, policyVersion), newGranted, source, moment,
        newGranted ? null : moment, version == null ? 1 : version + 1);
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
