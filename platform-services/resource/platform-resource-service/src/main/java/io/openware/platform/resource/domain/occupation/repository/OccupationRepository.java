package io.openware.platform.resource.domain.occupation.repository;

import io.openware.platform.resource.domain.occupation.model.Occupation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 资源占用仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。本端口的方法签名刻意保留了几处**并发契约**，
 * 它们是这个聚合的正确性核心，迁移时不得"简化"（见各方法说明）。
 */
public interface OccupationRepository {

  /**
   * 列出该租户下**存在有效占用**（HELD/RESERVED/IN_USE）的资源标识，用于可用性过滤。
   *
   * <p><b>跨租户说明</b>：实现按入参 {@code tenantId} 显式过滤并跳过租户拦截器
   * （{@code @InterceptorIgnore}）——调用方可能在没有租户上下文的场景下用它做批量判定，
   * 因此租户边界由参数而不是上下文决定。
   */
  List<Long> findActiveResourceIds(Long tenantId);

  /**
   * 在事务内**加行锁**读取某资源的有效占用（HELD/RESERVED/IN_USE）。
   *
   * <p><b>锁契约</b>：实现必须使用 {@code SELECT ... FOR UPDATE}，锁在事务提交或回滚前一直持有。
   * 这是"同一资源的并发预占不会双双通过重叠检查"的唯一保证；若退化为普通查询，两个并发请求会各自
   * 读到空集合然后各插一条重叠占用。
   */
  List<Occupation> findValidForUpdate(Long tenantId, Long resourceId);

  /** 按标识读取占用。 */
  Optional<Occupation> findById(Long occupationId);

  /** 新增占用（仅用于首次插入；状态迁移必须走下面两个条件更新）。 */
  Occupation insert(Occupation occupation);

  /**
   * 乐观锁释放：状态仍属有效且 {@code version} 匹配才生效，返回是否成功。
   *
   * <p><b>必须由单条 UPDATE 完成</b>，并在同一语句内把窗口终点**单向收缩**到本次释放时刻：
   * 收缩取值等价于 {@code GREATEST(start_at, LEAST(COALESCE(end_at, :now), :now))}——已经更早的
   * {@code end_at} 保持原值（不被反向拉长），{@code end_at} 为空时取释放时刻，外层的
   * {@code GREATEST(start_at, ...)} 兜住"占用尚未开始就被释放"而可能产生的负长度区间。
   *
   * <p>若不收缩，开台写入的 24 小时冲突窗口会留在库里，任何按 {@code start_at → end_at} 计算
   * 「实际使用时长／资源利用率」的读方都会把一次开台算成 24 小时。
   *
   * <p>若改为"先读后判再写"，两个并发释放都可能读到有效状态而各自写入，导致重复释放事件。
   */
  boolean releaseWithVersion(Long occupationId, int version, LocalDateTime releasedAt);

  /**
   * 乐观锁取消：状态为 HELD/RESERVED 且 {@code version} 匹配才生效，返回是否成功。
   *
   * <p>同样是单条条件更新；改为读改写会让并发取消重复生效。
   */
  boolean cancelWithVersion(Long occupationId, int version, LocalDateTime cancelledAt);

  /**
   * 扫描**已过期预占**（HELD 且 {@code holdExpiresAt} 已过）供定时任务消费。
   *
   * <p><b>跨租户</b>：定时任务不属于任何租户上下文，实现必须跳过租户拦截器（{@code @InterceptorIgnore}），
   * 否则只能扫到"当前无租户"下的 0 行。
   */
  List<Occupation> findExpiredHolds(LocalDateTime now, int limit);

  /**
   * 扫描**业务时段已结束**（{@code endAt} 已过）但仍处于有效状态的占用，供定时任务兜底自愈。
   *
   * <p>与 {@link #findExpiredHolds} 互补：后者看 HELD 的预占超时，这里看业务窗口结束。结台/取消/转台
   * 的释放若因服务抖动而失败，房态会一直显示"使用中"，由本扫描按 {@code endAt} 兜底。同样需要跨租户。
   */
  List<Occupation> findEndedOccupations(LocalDateTime now, int limit);
}
