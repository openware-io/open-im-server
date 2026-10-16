package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 储值流水一行 / 一页的对外视图。
 *
 * <p>字段集照搬原持久化对象 {@code CstWalletLedgerPo}（理由见 {@link WalletAccountView}）；
 * {@code tokenAmount} / {@code tokenBrandName} 由展示层填充。
 *
 * <p>分页响应的外形（{@code records} / {@code total} / {@code size} / {@code current} / {@code pages}）
 * 与 MyBatis-Plus 的 {@code Page} 序列化结果一致——调用方读的就是这些字段，换成别的名字同样是破坏。
 */
public record WalletLedgerPageView(
    List<WalletLedgerRowView> records,
    long total,
    long size,
    long current,
    long pages) {

  /** 由端口返回的一页构造。 */
  public static WalletLedgerPageView from(LedgerPage page, long pageNo, long pageSize) {
    List<WalletLedgerEntry> entries = page == null || page.entries() == null ? List.of() : page.entries();
    List<WalletLedgerRowView> rows = new java.util.ArrayList<>(entries.size());
    for (WalletLedgerEntry entry : entries) {
      rows.add(WalletLedgerRowView.from(entry));
    }
    long total = page == null ? 0L : page.total();
    long safeSize = pageSize < 1 ? 1 : pageSize;
    long pages = total == 0 ? 0 : (total + safeSize - 1) / safeSize;
    return new WalletLedgerPageView(rows, total, safeSize, pageNo, pages);
  }

  /** 流水一行。 */
  public record WalletLedgerRowView(
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
      LocalDateTime occurredAt,
      Long createdBy,
      LocalDateTime createdAt) {

    public static WalletLedgerRowView from(WalletLedgerEntry entry) {
      if (entry == null) {
        return null;
      }
      return new WalletLedgerRowView(entry.id(), entry.tenantId(), entry.storeId(),
          entry.walletAccountId(), entry.entryType(), entry.amount(), entry.balanceAfter(),
          entry.currencyCode(), entry.tokenAmount(), entry.tokenBrandName(), entry.orderId(),
          entry.fxQuoteId(), entry.idempotencyKey(), entry.occurredAt(), null, null);
    }

    /** 补齐代币展示字段。 */
    public WalletLedgerRowView withTokenDisplay(String token, String brand) {
      return new WalletLedgerRowView(id, tenantId, storeId, walletAccountId, entryType, amount,
          balanceAfter, currencyCode, token, brand, orderId, fxQuoteId, idempotencyKey, occurredAt,
          createdBy, createdAt);
    }
  }
}
