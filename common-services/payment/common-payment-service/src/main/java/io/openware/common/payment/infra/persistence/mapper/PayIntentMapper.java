package io.openware.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PayIntentMapper extends BaseMapper<PayIntentPo> {

  /**
   * 汇总某门店在时间区间内、**指定币种**的 CASH 成功收款（用于交班 expected_cash 对账）。
   *
   * <p>必须按币种过滤（16_CURRENCY_CONVENTIONS §5/§6）：交班长短款按币种分别盘点，
   * 把 CNY 与 USD 的现金静默相加会得出一个没有业务含义的「长短款」。
   * 币种取本班次的 {@code pay_shift.currency_code} 快照，而不是当前租户设置
   * （否则改设置会重算历史班次的 expected_cash）。
   */
  @Select("SELECT COALESCE(SUM(amount), 0) FROM pay_intent " +
      "WHERE tenant_id = #{tenantId} AND store_id = #{storeId} AND provider = 'CASH' " +
      "AND currency_code = #{currencyCode} " +
      "AND status = 'SUCCEEDED' AND created_at >= #{from} AND created_at < #{to}")
  BigDecimal sumCashSucceeded(@Param("tenantId") Long tenantId, @Param("storeId") Long storeId,
      @Param("currencyCode") String currencyCode,
      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
