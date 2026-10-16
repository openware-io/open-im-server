package io.openware.platform.customer.api.dto;

import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import java.time.LocalDateTime;

/**
 * 储值账户响应的对外视图。
 *
 * <p><b>字段集是刻意"照搬"的</b>：本接口此前直接返回持久化对象 {@code CstWalletAccountPo}，
 * 所以 tenantId / createdBy / createdAt / updatedBy / updatedAt 一直是响应的一部分。
 * 把它们删掉是一次 API 破坏（B 端与 C 端可能已在读），因此这里保留；
 * 按 standards/10 D2 它们本不该出现在契约里，收紧需要单独一次带版本说明的变更。
 *
 * <p>{@code tokenAmount} / {@code tokenBrandName} 由
 * {@link io.openware.platform.customer.application.WalletTokenDisplayService} 填（服务端唯一换算点）；
 * {@code accountOpened} 表示是否已开立真实账户行（懒初始化下零额视图为 false）。
 */
public record WalletAccountView(
    Long id,
    Long tenantId,
    Long customerId,
    Long legalEntityId,
    String currencyCode,
    Long availableAmount,
    Long frozenAmount,
    String tokenAmount,
    String tokenBrandName,
    Boolean accountOpened,
    String status,
    Integer version,
    Long createdBy,
    LocalDateTime createdAt,
    Long updatedBy,
    LocalDateTime updatedAt) {

  /** 由领域模型构造；代币字段留空，待展示层填充。 */
  public static WalletAccountView from(WalletAccount account) {
    if (account == null) {
      return null;
    }
    return new WalletAccountView(account.id(), account.tenantId(), account.customerId(),
        account.legalEntityId(), account.currencyCode(), account.available(), account.frozen(),
        account.tokenAmount(), account.tokenBrandName(),
        account.persisted(), account.status(), account.version(), null, null, null, null);
  }

  /** 补齐代币展示字段。 */
  public WalletAccountView withTokenDisplay(String tokenAmount, String tokenBrandName) {
    return new WalletAccountView(id, tenantId, customerId, legalEntityId, currencyCode,
        availableAmount, frozenAmount, tokenAmount, tokenBrandName, id != null, status, version,
        createdBy, createdAt, updatedBy, updatedAt);
  }
}
