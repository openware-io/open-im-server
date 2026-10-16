package io.openware.common.payment.domain.transaction.repository;

import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付交易仓储端口（对账汇总用只读面）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PaymentTransactionRepository {

  /**
   * 列出某租户在 **[{@code from}, {@code to})** 区间内**成功**的交易。
   *
   * <p>区间为**左闭右开**：{@code from}/{@code to} 通常是相邻两天的分界点，
   * 闭区间会把午夜那一笔同时算进两天，对账金额凭空翻倍。
   *
   * <p>币种不过滤：混币种是调用方必须显式处理的业务事实（见对账摘要），不能靠查询隐式藏掉。
   */
  List<PaymentTransaction> listSucceededBetween(Long tenantId, LocalDateTime from, LocalDateTime to);
}
