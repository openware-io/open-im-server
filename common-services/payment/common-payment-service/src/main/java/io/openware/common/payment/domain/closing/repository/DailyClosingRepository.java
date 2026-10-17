package io.openware.common.payment.domain.closing.repository;

import io.openware.common.payment.domain.channel.repository.Persisted;
import io.openware.common.payment.domain.closing.model.DailyClosing;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 日结仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface DailyClosingRepository {

  /**
   * 按**营业日** {@code businessDate} 的闭区间列出日结（时间倒序），两端为 {@code null} 表示该端不筛。
   *
   * <p>入参是 {@link LocalDate} 而不是 {@code LocalDateTime}：营业日本身没有时刻，
   * 让调用方先转成时刻再比较，会把"营业日 2026-09-30"能否被端点命中变成时区/时分秒的运气问题。
   */
  List<Persisted<DailyClosing>> listByBusinessDateBetween(LocalDate fromInclusive, LocalDate toInclusive);

  /**
   * 按门店 + 营业日读取日结记录。
   *
   * <p>唯一键是（租户, 门店, 营业日）：同一天只能有一份日结，重复提交要能被识别（而不是插入第二份）。
   */
  Optional<Persisted<DailyClosing>> findByStoreAndBusinessDate(Long tenantId, Long storeId, LocalDate businessDate);

  /** 新增日结记录；审计列由适配器按 {@code occurredAt} 填充。 */
  Persisted<DailyClosing> insert(DailyClosing closing, java.time.LocalDateTime occurredAt);
}
