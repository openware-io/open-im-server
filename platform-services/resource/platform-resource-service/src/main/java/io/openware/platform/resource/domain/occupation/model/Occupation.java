package io.openware.platform.resource.domain.occupation.model;

import java.time.LocalDateTime;

/**
 * 资源占用（领域模型）：某资源在某个时间窗口内被占用的凭据。
 *
 * <p>审计字段（createdBy/At、updatedBy/At）不在此承载：由持久化适配器填充。
 * {@code version} 保留：它是乐观锁并发语义，每次状态迁移自增。
 *
 * @param id               占用标识
 * @param tenantId         租户
 * @param storeId          门店
 * @param resourceId       被占用资源
 * @param sourceType       占用来源类型（订单／预占等）
 * @param sourceId         来源标识
 * @param startAt          业务窗口起点
 * @param endAt            业务窗口终点；释放时按单向收缩提前
 * @param status           状态：HELD / RESERVED / IN_USE / RELEASED / CANCELLED
 * @param holdExpiresAt    预占超时时刻（仅 HELD 有意义）
 * @param version          版本号（乐观锁）
 */
public record Occupation(
    Long id,
    Long tenantId,
    Long storeId,
    Long resourceId,
    String sourceType,
    Long sourceId,
    LocalDateTime startAt,
    LocalDateTime endAt,
    String status,
    LocalDateTime holdExpiresAt,
    Integer version) {

  /** 已预占、等待确认。 */
  public static final String STATUS_HELD = "HELD";

  /** 已预约。 */
  public static final String STATUS_RESERVED = "RESERVED";

  /** 使用中。 */
  public static final String STATUS_IN_USE = "IN_USE";

  /** 已释放。 */
  public static final String STATUS_RELEASED = "RELEASED";

  /** 已取消。 */
  public static final String STATUS_CANCELLED = "CANCELLED";

  /**
   * 新建预占：状态固定为 HELD、版本初值 0，尚未落库。
   *
   * <p>状态由本工厂固定而不是让调用方赋值：预占的初始状态是用例不变量。
   */
  public static Occupation hold(Long tenantId, Long storeId, Long resourceId, String sourceType,
                                Long sourceId, LocalDateTime startAt, LocalDateTime endAt,
                                LocalDateTime holdExpiresAt) {
    return new Occupation(null, tenantId, storeId, resourceId, sourceType, sourceId, startAt, endAt,
        STATUS_HELD, holdExpiresAt, 0);
  }

  /** 是否处于"有效占用"状态（会阻止同资源的新占用）。 */
  public boolean active() {
    return STATUS_HELD.equals(status) || STATUS_RESERVED.equals(status) || STATUS_IN_USE.equals(status);
  }

  /** 是否已释放。 */
  public boolean released() {
    return STATUS_RELEASED.equals(status);
  }

  /** 是否已取消。 */
  public boolean cancelled() {
    return STATUS_CANCELLED.equals(status);
  }

  /** 当前版本；为空时视为 0。 */
  public int currentVersion() {
    return version == null ? 0 : version;
  }

  /**
   * 与另一占用的时间窗口是否重叠。
   *
   * <p>半开区间语义：{@code [startAt, endAt)}——相邻窗口（前一个的 endAt 恰为后一个的 startAt）不算重叠。
   * 任一端为空视为该端不设界。
   */
  public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
    if (otherStart == null || otherEnd == null || startAt == null || endAt == null) {
      return true;
    }
    return startAt.isBefore(otherEnd) && otherStart.isBefore(endAt);
  }
}
