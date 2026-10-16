package io.openware.platform.customer.domain.point.repository;

import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
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
   * 流水一页。
   *
   * @param entries 本页条目（按 id 倒序）
   * @param total   满足条件的总条数
   */
  record LedgerPage(List<PointLedgerEntry> entries, long total) {
  }
}
