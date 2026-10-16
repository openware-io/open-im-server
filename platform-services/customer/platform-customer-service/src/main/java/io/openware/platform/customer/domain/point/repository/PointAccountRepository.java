package io.openware.platform.customer.domain.point.repository;

import io.openware.platform.customer.domain.account.model.AccountPurgeCount;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 积分账户与账本的仓储端口。
 *
 * <p>不暴露 MyBatis {@code Wrapper}、PO、Mapper 或 {@code IPage}/{@code Page}（见 standards/10
 * “端口命名与契约”）。分页以「偏移 + 上限」与总数表达，由适配器翻译成 LIMIT/OFFSET。
 *
 * <p>账本<b>只追加</b>：端口上没有"改一条历史流水"的方法，这是结构性的保证，不是约定。
 */
public interface PointAccountRepository {

  /** 按会员取积分账户（本域所有读写路径的定位口径：积分账户不分门店）。 */
  Optional<PointAccount> findByCustomer(Long customerId);

  /**
   * 新增账户，返回落库后的聚合（含回填主键与 {@code version = 0}）。
   *
   * <p>并发同会员同计划由唯一键 {@code uk_cst_point_account_scope} 兜底；撞键必须抛出而不是吞掉，
   * 否则调用方会拿着一个未落库的账户继续改余额。
   */
  PointAccount insert(PointAccount account);

  /**
   * 乐观锁写入可用余额：仅当库中 {@code version} 等于 {@code expectedVersion} 时写入并自增版本。
   *
   * <p>必须由单条条件 UPDATE（{@code ... WHERE id = ? AND version = ? SET available_points = ?,
   * version = version + 1}）完成，且版本自增写在 SQL 里：
   * <ul>
   *   <li>先读出来比对再无条件写回，会让两个并发扣减双双通过比对，后写者覆盖前者而双方都收到成功
   *       ——积分被扣两次而账本只记一次。</li>
   *   <li>用 Java 读出来的值加一，会覆盖并发期间的版本号。</li>
   * </ul>
   *
   * @return 是否更新成功；{@code false} = 期间已有他人写入，调用方必须拒绝，
   *     <b>不得</b>当作成功，也<b>不得</b>降级为无条件写入
   */
  boolean updateBalanceWithVersion(PointAccount account, int expectedVersion);

  /**
   * 追加一条账本流水。
   *
   * <p>不返回实体：调用方追加后只关心"已落库"，返回一个只有适配器才需要的行标识会诱使上层依赖它。
   * 幂等由唯一键 {@code uk_cst_point_ledger_idem(tenant_id, idempotency_key)} 兜底，撞键必须抛出。
   */
  void appendLedger(PointLedgerEntry entry);

  /** 幂等键是否已存在流水（需与唯一键 {@code uk_cst_point_ledger_idem} 共同兜底）。 */
  boolean ledgerExists(String idempotencyKey);

  /** 某账户的流水分页（按 id 倒序）。 */
  LedgerPage listLedger(Long accountId, Long storeId, long offset, long limit);

  /** 流水总条数。 */
  long countLedger(Long accountId, Long storeId);

  /**
   * 租户内<b>全部</b>积分账户（总部汇总用：余额合计，不按门店/会员过滤）。
   *
   * <p>只在只读汇总路径上使用；返回领域聚合而不是行数聚合，是为了让"余额合计"的口径
   * （{@code available()} 把 null 当 0）留在领域模型里，而不是散落在适配器的 SQL 里。
   */
  List<PointAccount> listAll();

  /**
   * 批量按会员取积分账户，键为会员 ID（积分管理列表避免逐行查询）。
   *
   * <p>缺失的会员不出现在结果里：调用方按"缺失 = 余额 0"处理（积分账户是懒创建的，绝大多数会员没有账户）。
   */
  java.util.Map<Long, PointAccount> findByCustomers(List<Long> customerIds);

  /**
   * 期间内账本净变动（总部汇总用）：{@code occurred_at} 落在闭区间 {@code [from, to]} 内。
   *
   * <p>积分变动不分方向（{@code points} 自带正负），与 {@link #listLedger} 的分页查询共用同一套
   * 筛选语义，保证"列表看到的流水"与"汇总算出的变动"不会出现两种口径。
   *
   * @param from    下界（含）；{@code null} = 不筛
   * @param to      上界（含）；{@code null} = 不筛
   * @param stores  门店集合；空或 {@code null} = 租户全量（非空时按 {@code store_id IN (...)} 过滤）
   */
  long deltaForPeriodByStores(LocalDateTime from, LocalDateTime to, List<Long> stores);

  /**
   * 清理某会员的积分账户及其零额账户的流水（{@code MemberPurgeApplicationService} 用）。
   *
   * <p><b>为什么是窄口方法而不是通用 {@code delete}</b>：这条路径只允许删该会员的积分账户，
   * 而有余额的账户在体检阶段就已经拒绝了清理。把条件开放给调用方（一组列名 / 一个 Wrapper）
   * 就等于把"该删哪些账户、哪些流水"这个判定搬出仓储——清理最不能出错的地方。
   *
   * <p><b>清理前逐字段核对过的既有语义（原样保留，没有顺手"修"）</b>：
   * 账户行是<b>该会员的全部积分账户</b>（原实现 {@code eq(customer_id, id)}）；
   * 流水只对<b>零额</b>账户删（原实现只遍历 available/frozen 都为 0 的账户）。
   * 也就是说非零账户的流水不会被这条路径删掉——账面事实因此不会被悄悄抹掉。
   * 改变它是行为变更，必须单独一次带版本说明的提交，不属于本次分层迁移。
   */
  AccountPurgeCount purgeAccountsFor(Long customerId);

  /**
   * 流水一页。
   *
   * @param entries 本页条目（按 id 倒序）
   * @param total   满足条件的总条数
   */
  record LedgerPage(List<PointLedgerEntry> entries, long total) {
  }
}
