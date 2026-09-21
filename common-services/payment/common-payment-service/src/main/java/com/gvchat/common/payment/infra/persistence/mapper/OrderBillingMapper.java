package com.gvchat.common.payment.infra.persistence.mapper;

import com.gvchat.common.payment.infra.persistence.po.OrderBillingPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OrderBillingMapper {
    @Select("""
            SELECT o.id, o.status, o.paid_amount, o.currency_code,
                   CASE WHEN o.total_amount > 0 THEN o.total_amount
                        ELSE GREATEST(0, COALESCE((SELECT SUM(i.total_amount)
                          FROM ord_order_item i WHERE i.tenant_id = o.tenant_id
                            AND i.order_id = o.id AND i.status = 'ACTIVE'), 0)
                          - COALESCE(o.discount_amount, 0) + COALESCE(o.tax_amount, 0)) END AS total_amount
              FROM ord_order o
             WHERE o.tenant_id = #{tenantId} AND o.id = #{orderId}
             FOR UPDATE
            """)
    OrderBillingPo selectForUpdate(@Param("tenantId") Long tenantId, @Param("orderId") Long orderId);

    /**
     * 订单币种快照（退款必须退**原币种**：跨币种退款是合规禁区，见 16_CURRENCY_CONVENTIONS §6.3）。
     * 只读、无锁；订单不存在返回 null，调用方按「未知 → 缺省当时租户币种」处理。
     */
    @Select("""
            SELECT o.currency_code
              FROM ord_order o
             WHERE o.tenant_id = #{tenantId} AND o.id = #{orderId}
            """)
    String selectCurrencyCode(@Param("tenantId") Long tenantId, @Param("orderId") Long orderId);

    @Select("""
            SELECT o.customer_id
              FROM ord_order o
              JOIN cst_member m ON m.id = o.customer_id AND m.tenant_id = o.tenant_id
             WHERE o.tenant_id = #{tenantId} AND o.id = #{orderId} AND m.account_id = #{accountId}
            """)
    Long selectOwnedCustomerId(@Param("tenantId") Long tenantId, @Param("orderId") Long orderId,
                               @Param("accountId") Long accountId);

    /**
     * 收款落账：更新已收金额与状态（足额即 COMPLETED）。
     * refundable_amount 同步为已收金额（可退 = 已收，暂无退款回冲流程），
     * 否则未收款订单的「可退金额」会与实收金额口径不一致。
     */
    @Update("""
            UPDATE ord_order
               SET paid_amount = #{newPaidAmount},
                   refundable_amount = #{newPaidAmount},
                   status = CASE WHEN #{newPaidAmount} >= #{totalAmount} THEN 'COMPLETED' ELSE status END,
                   completed_at = CASE WHEN #{newPaidAmount} >= #{totalAmount} THEN CURRENT_TIMESTAMP(3) ELSE completed_at END,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE tenant_id = #{tenantId} AND id = #{orderId}
               AND paid_amount = #{expectedPaidAmount}
               AND status NOT IN ('CANCELLED', 'VOIDED', 'COMPLETED')
            """)
    int markPaid(@Param("tenantId") Long tenantId,
                 @Param("orderId") Long orderId,
                 @Param("expectedPaidAmount") BigDecimal expectedPaidAmount,
                 @Param("newPaidAmount") BigDecimal newPaidAmount,
                 @Param("totalAmount") BigDecimal totalAmount);
}
