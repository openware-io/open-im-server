package io.openware.platform.customer.domain.wallet.repository;

import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 储值账户与账本的仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或 {@code IPage}/{@code Page}（见 standards/10
 * “端口命名与契约”）。分页以"偏移 + 上限"与总数表达，由适配器翻译成 MyBatis-Plus 的分页对象。
 */
public interface WalletAccountRepository {

  /** 按会员 + 币种取账户（充值路径的定位口径；同会员不同币种是各自独立的账户）。 */
  Optional<WalletAccount> findByCustomerAndCurrency(Long customerId, String currencyCode);

  /** 按会员取账户（消费/退还/释放/查询路径的口径）。 */
  Optional<WalletAccount> findByCustomer(Long customerId);

  /** 按账户标识取账户（CAS 冲突后重新读取最新余额与版本）。 */
  Optional<WalletAccount> findById(Long accountId);

  /** 批量按会员取账户，键为会员 ID（列表页避免逐行查询）。 */
  Map<Long, WalletAccount> findByCustomers(List<Long> customerIds);

  /**
   * 新增账户，返回落库后的聚合（含回填主键与 version=0）。
   *
   * <p>并发同会员同币种由唯一键兜底；撞键必须抛出而不是吞掉，否则调用方会拿着一个未落库的账户继续扣款。
   */
  WalletAccount insert(WalletAccount account);

  /**
   * 乐观锁写入余额：仅当库中 {@code version} 等于 {@code expectedVersion} 时更新余额并自增版本。
   *
   * @return 是否更新成功；{@code false} = 期间已有他人写入，调用方必须重试或拒绝，**不得**当作成功
   */
  boolean updateBalanceWithVersion(WalletAccount account, int expectedVersion);

  /**
   * 追加一条账本流水。
   *
   * <p>不返回实体：调用方在追加后只关心"已落库"，返回一个只有适配器才需要的行标识会诱使上层依赖它。
   * 幂等由唯一键 {@code uk_cst_wallet_ledger_idem} 兜底，撞键必须抛出。
   */
  void insertLedger(WalletLedgerEntry entry);

  /** 幂等键是否已存在流水（需与唯一键 {@code uk_cst_wallet_ledger_idem} 共同兜底）。 */
  boolean ledgerExists(String idempotencyKey);

  /** 某账户的流水分页（按 id 倒序）。 */
  LedgerPage listLedger(Long walletAccountId, Long storeId, long offset, long limit);

  /** 流水总条数。 */
  long countLedger(Long walletAccountId, Long storeId);

  /**
   * 流水一页。
   *
   * @param entries 本页条目（按 id 倒序）
   * @param total   满足条件的总条数
   */
  record LedgerPage(List<WalletLedgerEntry> entries, long total) {
  }
}
