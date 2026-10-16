package io.openware.platform.customer.domain.point.model;

/**
 * 积分账户（领域模型）。
 *
 * <p><b>积分是「个数」</b>（1 积分 = 1 个，1:1 不换算）：与储值账户不同，这里没有币种字段，
 * 也没有小数——{@code availablePoints} / {@code frozenPoints} 就是整数个数。
 *
 * <p>审计字段（createdBy、createdAt、updatedBy、updatedAt）不在此承载：由适配器填充
 * （与 {@code WalletAccount} / {@code PointRuleConfig} 同一约定）。
 *
 * <p>{@code version} 是<b>乐观锁版本号，且真的参与写入判定</b>：本表没有其他并发保护手段
 * （PO 上没有 {@code @Version}，工程里也没有 {@code OptimisticLockerInnerInterceptor}），
 * 而「读余额 → 判定够不够 → 写回余额」若不带版本条件，两个并发扣减会各自覆盖对方。
 * 写入一律经 {@code PointAccountRepository#updateBalanceWithVersion} 的单条条件 UPDATE。
 *
 * @param id              账户标识；{@code null} 表示尚未落库
 * @param tenantId        租户
 * @param customerId      会员档案（cst_member.id）
 * @param programId       积分计划；懒创建时取 {@link #DEFAULT_PROGRAM_ID}
 * @param availablePoints 可用积分（个数）
 * @param frozenPoints    冻结积分（个数）
 * @param version         乐观锁版本号
 */
public record PointAccount(
    Long id,
    Long tenantId,
    Long customerId,
    Long programId,
    Long availablePoints,
    Long frozenPoints,
    Integer version) {

  /** 懒创建积分账户时的默认积分计划（既有行为，不变）。 */
  public static final long DEFAULT_PROGRAM_ID = 1L;

  /** 可用积分（为空视为 0）。 */
  public long available() {
    return availablePoints == null ? 0L : availablePoints;
  }

  /** 冻结积分（为空视为 0）。 */
  public long frozen() {
    return frozenPoints == null ? 0L : frozenPoints;
  }

  /** 版本号（为空视为 0）。 */
  public int currentVersion() {
    return version == null ? 0 : version;
  }

  /** 是否已落库（{@code id == null} 即尚未落库）。 */
  public boolean persisted() {
    return id != null;
  }

  /** 可用积分是否够扣。 */
  public boolean canCover(long points) {
    return points > 0 && available() >= points;
  }

  /**
   * 增加可用积分（返回新实例，不改自身）。
   *
   * @throws IllegalArgumentException 非正数——方向由调用方表达，负数走 {@link #deduct(long)}
   */
  public PointAccount credit(long points) {
    if (points <= 0) {
      throw new IllegalArgumentException("points must be positive");
    }
    return new PointAccount(id, tenantId, customerId, programId, available() + points, frozen(), version);
  }

  /**
   * 扣减可用积分（返回新实例，不改自身）。
   *
   * @throws IllegalArgumentException 非正数
   * @throws IllegalStateException    余额不足——判定与扣减必须由调用方在同一临界区内完成
   */
  public PointAccount deduct(long points) {
    if (points <= 0) {
      throw new IllegalArgumentException("points must be positive");
    }
    if (available() < points) {
      throw new IllegalStateException("LEDGER_INSUFFICIENT");
    }
    return new PointAccount(id, tenantId, customerId, programId, available() - points, frozen(), version);
  }

  /**
   * 把版本改写为落库后的版本（CAS 成功时 SQL 里 {@code version = version + 1}，Java 侧据此回填）。
   *
   * <p>只改版本号，不改余额：余额是本次写入的候选值，必须原样带出去，不能从别处重算。
   */
  public PointAccount withVersion(int newVersion) {
    return new PointAccount(id, tenantId, customerId, programId, availablePoints, frozenPoints, newVersion);
  }

  /** 新建账户（尚未落库，余额 0，版本 0，默认积分计划）。 */
  public static PointAccount open(Long customerId) {
    return new PointAccount(null, null, customerId, DEFAULT_PROGRAM_ID, 0L, 0L, 0);
  }
}
