package io.openware.platform.tenant.domain.currency.model;

/**
 * 钱包/储值账户的**币种视图**（tenant 服务侧的只读投影）。
 *
 * <p><b>为什么在 tenant 域里定义</b>：币种切换（standards/16 §2.2.2）必须做"非零余额且币种不同"的
 * 保护判定，而余额事实位于另一限界上下文。按 standards/10“跨上下文读取”，只读的跨上下文查询可以定义
 * **专用只读端口，且端口归属调用方**——因此这个投影只含判定所需字段，不是那张表的完整模型，
 * 也不允许 tenant 域借它去改余额。
 *
 * <p>{@code availableAmount}/{@code frozenAmount} 是**最小货币单位**的整数（与 DDL 一致），
 * 不用浮点：金额比较必须精确。
 *
 * @param id              账户标识
 * @param tenantId        租户
 * @param customerId      客户
 * @param legalEntityId   法人主体；可为空
 * @param currencyCode    账户币种
 * @param availableAmount 可用余额（最小货币单位）
 * @param frozenAmount    冻结金额（最小货币单位）
 */
public record WalletAccount(
    Long id,
    Long tenantId,
    Long customerId,
    Long legalEntityId,
    String currencyCode,
    Long availableAmount,
    Long frozenAmount) {

  /**
   * 是否存在**非零余额**（可用或冻结任一非零）。
   *
   * <p>这正是 §2.2.2 的判据：非零余额的账户改币种等于按 1:1 数字折算真实资产，
   * 属禁区，必须显式确认才允许。判空安全：金额字段为空视为零。
   */
  public boolean hasBalance() {
    return (availableAmount != null && availableAmount != 0L)
        || (frozenAmount != null && frozenAmount != 0L);
  }

  /** 币种是否与给定编码不同（大小写不敏感）。 */
  public boolean currencyDiffersFrom(String otherCode) {
    return currencyCode == null || otherCode == null || !currencyCode.equalsIgnoreCase(otherCode);
  }
}
