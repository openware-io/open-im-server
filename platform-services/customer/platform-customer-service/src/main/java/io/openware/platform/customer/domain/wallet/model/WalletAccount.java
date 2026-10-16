package io.openware.platform.customer.domain.wallet.model;

/**
 * 储值账户（领域模型）。
 *
 * <p><b>金额一律是 Long 最小货币单位整数</b>，不是 BigDecimal：储值不允许小数分位，
 * 用浮点/小数表示余额会让"够不够扣"的判定出现 0.1+0.2 类误差。{@code tokenAmount} 例外——
 * 它是**代币数量**（可为小数），且只是展示快照，不参与余额判定。
 *
 * <p>审计字段（createdBy、updatedBy、createdAt、updatedAt）不在此承载：由适配器填充。
 *
 * <p>{@code tokenAmount}、{@code tokenBrandName}、{@code accountOpened} 是**展示字段，不是列**
 * （PO 上标了 {@code @TableField(exist = false)}，DDL 里也没有）：代币数量由
 * {@code WalletTokenDisplayService} 在响应期按"余额 ÷ 100 × 租户 ratio"算出。
 * 它们出现在本模型里是为了让视图能从一处装配，但**绝不能进入任何 UPDATE 语句**——
 * 对 `exist = false` 的字段生成列引用会在运行时抛 "can not find lambda cache for this property"。
 *
 * <p>{@code version} 是**乐观锁版本号**，必须参与写入判定：本表没有其他并发保护手段，
 * 而"读余额 → 判定够不够 → 写回余额"若不带版本条件，两个并发扣减会各自覆盖对方。
 *
 * @param id               账户标识；{@code null} 表示尚未落库
 * @param tenantId         租户
 * @param customerId       会员
 * @param legalEntityId    法人主体（储值资金归属）
 * @param currencyCode     币种快照
 * @param availableAmount  可用余额（最小货币单位）
 * @param frozenAmount     冻结金额（最小货币单位）
 * @param tokenAmount      代币数量（展示用，可为小数）
 * @param tokenBrandName   代币品牌名
 * @param accountOpened    是否已开户
 * @param status           状态：ACTIVE / …
 * @param version          乐观锁版本号
 */
public record WalletAccount(
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
    Integer version) {

  /** 生效中。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** 可用余额（为空视为 0）。 */
  public long available() {
    return availableAmount == null ? 0L : availableAmount;
  }

  /** 冻结金额（为空视为 0）。 */
  public long frozen() {
    return frozenAmount == null ? 0L : frozenAmount;
  }

  /** 版本号（为空视为 0）。 */
  public int currentVersion() {
    return version == null ? 0 : version;
  }

  /** 是否尚未落库（零额只读视图或待插入的新账户）。 */
  public boolean persisted() {
    return id != null;
  }

  /**
   * 零额只读视图：**没有账户**时的余额口径。
   *
   * <p>{@code id == null}，因此不可能被当成真实账户写回或审计——这是刻意的：
   * 为一次失败的扣减建账户会把"余额不足"变成一个持久化的空账户。
   */
  public static WalletAccount zeroFor(Long customerId, Long legalEntityId, String currencyCode) {
    return new WalletAccount(null, null, customerId, legalEntityId, currencyCode, 0L, 0L,
        null, null, false, STATUS_ACTIVE, 0);
  }

  /** 新账户（余额 0，版本 0）。 */
  public static WalletAccount open(Long customerId, Long legalEntityId, String currencyCode) {
    return new WalletAccount(null, null, customerId, legalEntityId, currencyCode, 0L, 0L,
        null, null, false, STATUS_ACTIVE, 0);
  }

  /**
   * 扣减可用余额（返回新实例，不改自身）。
   *
   * @throws IllegalStateException 余额不足时抛出——判定与扣减必须由调用方在同一临界区内完成
   */
  public WalletAccount deduct(long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (available() < amount) {
      throw new IllegalStateException("LEDGER_INSUFFICIENT");
    }
    return new WalletAccount(id, tenantId, customerId, legalEntityId, currencyCode,
        available() - amount, frozen(), tokenAmount, tokenBrandName, accountOpened, status, version);
  }

  /** 增加可用余额（返回新实例）。 */
  public WalletAccount credit(long amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    return new WalletAccount(id, tenantId, customerId, legalEntityId, currencyCode,
        available() + amount, frozen(), tokenAmount, tokenBrandName, accountOpened, status, version);
  }

  /** 余额是否够扣。 */
  public boolean canCover(long amount) {
    return amount > 0 && available() >= amount;
  }

  /** 币种编码；为空表示未确定。归一（大小写/非法值回退）由应用层用 Currency 完成，领域不依赖 infrastructure。 */
  public String currencyCodeOrNull() {
    return currencyCode == null || currencyCode.isBlank() ? null : currencyCode;
  }

  /** 是否与给定币种（**已归一**）不同；账户币种未确定时返回 false（交由调用方补齐）。 */
  public boolean differsFromNormalizedCurrency(String normalizedCurrencyCode) {
    String account = currencyCodeOrNull();
    return account != null && normalizedCurrencyCode != null && !account.equals(normalizedCurrencyCode);
  }
}
