package io.openware.common.payment.domain.collect.repository;

import io.openware.common.payment.domain.collect.model.PayCollect;
import java.util.List;

/**
 * 收款单仓储端口（只读：本域对收款单只有回看用途，写入在收款流程内部）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PayCollectRepository {

  /**
   * 按订单（单租户、批量）列出**已确认**收款（按创建时间、标识升序）。
   *
   * <p>只返回 {@code CONFIRMED}：{@code INIT}/{@code HOLD}/{@code FAILED} 是尝试而非收款，
   * 把它们算进"已收金额"会让订单看起来收过钱。
   *
   * <p>租户边界由入参显式给定，不依赖拦截器兜底——这是平台侧的批量核对路径。
   */
  List<PayCollect> listConfirmedByOrderIds(Long tenantId, List<Long> orderIds);

  /** 按订单（单租户、批量）列出收款单（不限状态）。 */
  List<PayCollect> listByOrderIds(Long tenantId, List<Long> orderIds);
}
