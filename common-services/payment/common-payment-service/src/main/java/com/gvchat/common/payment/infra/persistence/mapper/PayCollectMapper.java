package com.gvchat.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.payment.infra.persistence.po.PayCollectPo;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PayCollectMapper extends BaseMapper<PayCollectPo> {

  /**
   * 日结用：某门店在时间区间内**已确认**的组合收款行（币种快照 + 分腿响应）。
   *
   * <p><b>为什么需要这条查询</b>：储值币（WALLET）与积分（POINT）这两种**支付工具**的抵扣不落
   * {@code pay_intent}——{@code CollectApplicationService.recordCash} 只对现金类分腿写
   * pay_intent/pay_transaction，跨域的储值/积分扣减落在 customer 域账本，本服务内只剩
   * {@code pay_collect.response_json} 的分腿记录（每条只有 {@code method} + **抵扣的账单金额**，
   * 没有独立的代币/积分数量）。要按支付构成口径统计这类抵扣，必须回读这里。
   *
   * <p><b>为什么必须带门店条件</b>：{@code pay_collect} 自身没有门店列（{@code V1__pay_baseline.sql}），
   * 只能经 {@code ord_order.store_id} 定位——与 {@link RefundMapper#sumRefundedByCurrency}
   * 及管理端报表同一口径。
   *
   * <p><b>状态口径</b>：只统计 {@code state='CONFIRMED'}（组合收款成功终态）；INIT/FAILED 尚未形成资金事实。
   *
   * <p><b>时间口径</b>：{@code created_at}（组合收款落库时间），与日结收款侧
   * {@code pay_intent.created_at} 的窗口语义一致。
   *
   * <p><b>币种口径</b>：取 {@code pay_collect.currency_code} 快照（收款币种必须等于订单币种），
   * 为空的历史行才回退订单币种快照；**禁止**按当前租户币种解释历史收款。
   *
   * @return 每笔已确认收款一行（无则空列表，由调用方按 0 处理）
   */
  @Select("""
          SELECT COALESCE(c.currency_code, o.currency_code) AS currency_code,
                 c.response_json AS response_json
            FROM pay_collect c
            JOIN ord_order o ON o.id = c.order_id AND o.tenant_id = c.tenant_id
           WHERE c.tenant_id = #{tenantId}
             AND o.store_id = #{storeId}
             AND c.state = 'CONFIRMED'
             AND c.created_at >= #{from} AND c.created_at < #{to}
          """)
  List<ConfirmedCollectRow> selectConfirmedByStoreAndWindow(@Param("tenantId") Long tenantId,
      @Param("storeId") Long storeId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** 已确认组合收款行（只取币种快照与分腿响应，其余列不参与日结汇总）。 */
  @Getter
  @Setter
  class ConfirmedCollectRow {
    private String currencyCode;
    private String responseJson;
  }
}
