package io.openware.platform.customer.domain.wallet.repository;

import io.openware.platform.customer.domain.account.model.AccountPurgeCount;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import java.time.LocalDateTime;
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
   * 租户内<b>全部</b>储值账户（总部汇总用：余额合计，不按门店/会员过滤）。
   *
   * <p>只在只读汇总路径上使用；返回领域聚合而不是行数聚合，是为了让"余额合计"的口径
   * （{@code available()} 把 null 当 0）留在领域模型里，而不是散落在适配器的 SQL 里。
   */
  List<WalletAccount> listAll();

  /**
   * 期间内账本净变动（总部汇总用）：{@code occurred_at} 落在闭区间 {@code [from, to]} 内。
   *
   * <p>方向口径与业务一致：{@code CONSUME} / {@code REFUND} 记负、其余记正。
   * 与 {@link #listLedger} 的分页查询共用同一套筛选语义，保证"列表看到的流水"与"汇总算出的变动"
   * 不会出现两种口径。
   *
   * @param from    下界（含）；{@code null} = 不筛
   * @param to      上界（含）；{@code null} = 不筛
   * @param stores  门店集合；空或 {@code null} = 租户全量（非空时按 {@code store_id IN (...)} 过滤）
   */
  long deltaForPeriodByStores(LocalDateTime from, LocalDateTime to, List<Long> stores);

  /**
   * 清理某会员的<b>零额</b>储值账户及其账本流水（{@code MemberPurgeApplicationService} 用）。
   *
   * <p><b>为什么是窄口方法而不是通用 {@code delete}</b>：这条路径只允许删"可用与冻结都为 0"的账户
   * （有余额的账户是业务引用，体检已经因此拒绝清理）。把条件开放给调用方（例如传一组列名或一个
   * Wrapper）就等于把"什么算空账户"这个判定搬出仓储——那正是清理最不能出错的地方。
   * 顺序也是固定的：先删该账户的流水，再删账户行（账户行被删而流水留下就是孤儿）。
   */
  AccountPurgeCount purgeEmptyAccountsFor(Long customerId);

  /**
   * 流水一页。
   *
   * @param entries 本页条目（按 id 倒序）
   * @param total   满足条件的总条数
   */
  record LedgerPage(List<WalletLedgerEntry> entries, long total) {
  }
}
