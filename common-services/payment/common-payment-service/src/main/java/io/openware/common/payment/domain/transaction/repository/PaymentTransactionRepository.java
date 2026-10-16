package io.openware.common.payment.domain.transaction.repository;

import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付交易仓储端口。
 *
 * <p>读写两面：写面供组合收款的现金/线上分腿落流水（与收款单状态机同一事务），
 * 读面供对账汇总与订单收款明细回看。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface PaymentTransactionRepository {

  /**
   * 新增交易，返回落库后的聚合（含回填主键）。
   *
   * <p>实现补齐审计字段（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值，必须由写入方提供）；
   * 操作人列留默认。
   */
  PaymentTransaction insert(PaymentTransaction transaction);

  /**
   * 列出某租户在 **[{@code from}, {@code to})** 区间内**成功**的交易。
   *
   * <p>区间为**左闭右开**：{@code from}/{@code to} 通常是相邻两天的分界点，
   * 闭区间会把午夜那一笔同时算进两天，对账金额凭空翻倍。
   *
   * <p>币种不过滤：混币种是调用方必须显式处理的业务事实（见对账摘要），不能靠查询隐式藏掉。
   */
  List<PaymentTransaction> listSucceededBetween(Long tenantId, LocalDateTime from, LocalDateTime to);

  /**
   * 按支付意图集合（单租户、批量）列出交易（按发生时刻、标识升序）。
   *
   * <p>不按状态过滤：读模型要把失败/处理中的流水一并展示，让运营看到"这笔尝试了但没成功"。
   */
  List<PaymentTransaction> listByPaymentIntentIds(Long tenantId, List<Long> paymentIntentIds);
}
