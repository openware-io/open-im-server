package io.openware.common.payment.infra.persistence.mapper;

import io.openware.common.payment.infra.persistence.po.OrderBillingPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OrderBillingMapper {
    @Select("""
            SELECT o.id, o.trade_status, o.paid_amount, o.currency_code,
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
            SELECT o.store_id
              FROM ord_order o
             WHERE o.tenant_id = #{tenantId} AND o.id = #{orderId}
            """)
    Long selectStoreId(@Param("tenantId") Long tenantId, @Param("orderId") Long orderId);

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
     * 收款落账：更新已收金额与交易状态（足额 → {@code PAID}，未足额 → {@code PENDING_PAYMENT}）。
     * refundable_amount 同步为已收金额（可退 = 已收，暂无退款回冲流程），
     * 否则未收款订单的「可退金额」会与实收金额口径不一致。
     *
     * <p>订单主表只有 {@code trade_status}：兼容列 {@code status} 已随收口迁移删除，不再双写。
     */
    @Update("""
            UPDATE ord_order
               SET paid_amount = #{newPaidAmount},
                   refundable_amount = #{newPaidAmount},
                   trade_status = CASE WHEN #{newPaidAmount} >= #{totalAmount} THEN 'PAID' ELSE 'PENDING_PAYMENT' END,
                   completed_at = CASE WHEN #{newPaidAmount} >= #{totalAmount} THEN CURRENT_TIMESTAMP(3) ELSE completed_at END,
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE tenant_id = #{tenantId} AND id = #{orderId}
               AND paid_amount = #{expectedPaidAmount}
               AND trade_status NOT IN ('CANCELLED', 'VOIDED', 'COMPLETED')
            """)
    int markPaid(@Param("tenantId") Long tenantId,
                 @Param("orderId") Long orderId,
                 @Param("expectedPaidAmount") BigDecimal expectedPaidAmount,
                 @Param("newPaidAmount") BigDecimal newPaidAmount,
                 @Param("totalAmount") BigDecimal totalAmount);

    /**
     * 退款落账：回冲已收与可退，金额不为负。
     *
     * <p><b>退款状态同步</b>：{@code refund_status} 表达退款程度——
     * 退到已收金额归零 → {@code FULL}，否则 {@code PARTIAL}。
     * {@code refund_status} 的赋值放在 {@code paid_amount} 之前：MySQL 按从左到右求值，
     * 放在后面会用到已经回冲过的金额，把部分退款误判成全额。
     *
     * <p>显式取消/作废的订单不可退款：判据是 {@code trade_status}（兼容列 status 已删除）。
     */
    @Update("""
            UPDATE ord_order
               SET refund_status = CASE WHEN #{refundAmount} >= paid_amount THEN 'FULL' ELSE 'PARTIAL' END,
                   paid_amount = GREATEST(0, paid_amount - #{refundAmount}),
                   refundable_amount = GREATEST(0, refundable_amount - #{refundAmount}),
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE tenant_id = #{tenantId} AND id = #{orderId}
               AND trade_status NOT IN ('CANCELLED', 'VOIDED')
            """)
    int markRefunded(@Param("tenantId") Long tenantId,
                     @Param("orderId") Long orderId,
                     @Param("refundAmount") BigDecimal refundAmount);

}
