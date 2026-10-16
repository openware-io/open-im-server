package io.openware.platform.customer.domain.wallet.model;

import java.time.LocalDateTime;

/**
 * 储值流水（账本）条目。
 *
 * <p>账本**自证币种**（{@code currencyCode} 取账户币种快照），不依赖回查账户：
 * 账户币种将来若被改动，"账户 ↔ 流水"就会显示两种币种，而对账只能相信流水当时的事实。
 *
 * @param id              流水标识
 * @param tenantId        租户
 * @param storeId         发生门店；{@code null} 表示非门店场景
 * @param walletAccountId 所属账户
 * @param entryType       类型：RECHARGE / REFUND / CONSUME / RELEASE
 * @param amount          发生金额（最小货币单位，正数；方向由 entryType 表达）
 * @param balanceAfter    发生后的可用余额
 * @param currencyCode    币种快照
 * @param tokenAmount     代币数量快照
 * @param tokenBrandName  代币品牌名快照
 * @param orderId         关联订单
 * @param fxQuoteId       关联汇率报价（跨币种场景留痕）
 * @param idempotencyKey  幂等键；唯一键 {@code uk_cst_wallet_ledger_idem} 兜底
 * @param occurredAt      发生时刻
 */
public record WalletLedgerEntry(
    Long id,
    Long tenantId,
    Long storeId,
    Long walletAccountId,
    String entryType,
    Long amount,
    Long balanceAfter,
    String currencyCode,
    String tokenAmount,
    String tokenBrandName,
    Long orderId,
    Long fxQuoteId,
    String idempotencyKey,
    LocalDateTime occurredAt) {

  /** 充值。 */
  public static final String TYPE_RECHARGE = "RECHARGE";
  /** 退还（余额扣减）。 */
  public static final String TYPE_REFUND = "REFUND";
  /** 消费（组合收款抵扣）。 */
  public static final String TYPE_CONSUME = "CONSUME";
  /** 释放（失败补偿归还）。 */
  public static final String TYPE_RELEASE = "RELEASE";

  /** 发生金额（为空视为 0）。 */
  public long amountOrZero() {
    return amount == null ? 0L : amount;
  }

  /** 发生后余额（为空视为 0）。 */
  public long balanceAfterOrZero() {
    return balanceAfter == null ? 0L : balanceAfter;
  }
}
