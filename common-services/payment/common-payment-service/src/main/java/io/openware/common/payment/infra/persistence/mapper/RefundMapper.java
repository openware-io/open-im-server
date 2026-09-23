package io.openware.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.infra.persistence.po.RefundPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundMapper extends BaseMapper<RefundPo> {

  /**
   * 日结用：某门店在时间区间内**已退款**的按币种合计（笔数 + 金额）。
   *
   * <p><b>为什么必须带门店条件</b>：{@code pay_refund} 自身没有门店列（{@code V1__pay_baseline.sql:23-32}），
   * 只能经 {@code ord_order.store_id} 定位——与管理端报表 {@code ReportMapper.selectRefunds} 同一口径。
   *
   * <p><b>状态口径</b>：只统计本服务退款状态机的终态 {@code REFUNDED}（财务登记线下退款成功，
   * {@code RefundApplicationService#markRefunded}）；PENDING/APPROVED/REJECTED 尚未形成资金事实，不入日结。
   *
   * <p><b>时间口径</b>：{@code updated_at}——{@code markRefunded} 把状态推进到 REFUNDED 的同一事务里刷新，
   * 即「退款完成时间」；没有独立的 refunded_at 列，不用 created_at（申请时间）避免把跨日审批算进错误的营业日。
   *
   * <p><b>金额口径</b>：{@code approved_amount} 优先（实际批准金额），缺失时回退 {@code requested_amount}；
   * 一律最小货币单位整数（CNY 分 / USD cent）。
   *
   * <p><b>币种口径</b>：取 {@code pay_refund.currency_code} 快照（{@code V10__pay_currency_snapshot.sql:22-24}，
   * 退款退原币种），为空的历史行才回退订单币种快照；**禁止**按当前租户币种解释历史退款。
   *
   * @return 每个出现过的币种一行；无退款时返回空列表（由调用方按 0 处理）
   */
  @Select("""
          SELECT COALESCE(r.currency_code, o.currency_code) AS currency_code,
                 COUNT(*) AS refund_count,
                 COALESCE(SUM(COALESCE(r.approved_amount, r.requested_amount, 0)), 0) AS refund_amount
            FROM pay_refund r
            JOIN ord_order o ON o.id = r.order_id AND o.tenant_id = r.tenant_id
           WHERE r.tenant_id = #{tenantId}
             AND o.store_id = #{storeId}
             AND r.status = 'REFUNDED'
             AND r.updated_at >= #{from} AND r.updated_at < #{to}
           GROUP BY COALESCE(r.currency_code, o.currency_code)
          """)
  List<RefundCurrencyAggregate> sumRefundedByCurrency(@Param("tenantId") Long tenantId,
      @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** 已退款按币种合计行（{@code refund_amount} 为最小货币单位整数）。 */
  @Getter
  @Setter
  class RefundCurrencyAggregate {
    private String currencyCode;
    private long refundCount;
    private BigDecimal refundAmount;
  }
}
