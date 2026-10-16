package io.openware.common.payment.domain.shift.repository;

import io.openware.common.payment.domain.shift.model.Shift;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 交班仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface ShiftRepository {

  /**
   * 按**交班业务时间** {@code openedAt} 的闭区间列出交班（时间倒序），两端为 {@code null} 表示该端不筛。
   *
   * <p>用 {@code openedAt} 而不是记录的创建时间：交班的归属由开班时刻决定，
   * 补录一条昨天的交班不该出现在"今天"的列表里。
   */
  List<Shift> listByOpenedAtBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive);

  /** 按标识读取班次。 */
  Optional<Shift> findById(Long shiftId);

  /**
   * 列出某门店在 {@code [from, to)} 内**已交班**（CLOSED / REVIEW_REQUIRED）的班次。
   *
   * <p>左闭右开：窗口端点通常是营业日边界，闭区间会把同一笔长短款算进相邻两天。
   *
   * <p>状态以入参给出而不是在适配器里写死：状态集合属于业务口径
   * （"已交班"意味着哪些状态由领域决定），换集合不该改 SQL。
   */
  List<Shift> listClosedBetween(Long tenantId, Long storeId, List<String> closedStatuses,
                                LocalDateTime from, LocalDateTime to);

  /** 新增班次（开班），返回落库后的聚合（含回填主键）。 */
  Shift insert(Shift shift);

  /**
   * 保存交班结账结果（对账字段）。
   *
   * <p>只写结账相关列：开班事实（门店、终端、操作人、开班时刻、备用金、币种快照）是这条班次的身份，
   * 结账时不应被重写——重写会让"开班时的币种快照"随结账时的租户设置变化。
   */
  boolean updateClosing(Shift shift);
}
