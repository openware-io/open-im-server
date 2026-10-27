package io.openware.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.infra.persistence.po.ChannelOrderSnapshotPo;
import io.openware.common.payment.infra.persistence.po.PayChannelSettlementPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 渠道结算持久化 Mapper。
 *
 * <p>结算台账用 MyBatis-Plus 基线；订单口径投影用显式 SQL——它是具名读模型，
 * 只取结算判定真正需要的列，不用 Wrapper 把 {@code ord_order} 的宽表结构带进支付域。
 */
@Mapper
public interface ChannelSettlementMapper extends BaseMapper<PayChannelSettlementPo> {

    /**
     * 按订单标识只读订单结算口径（不加锁；用于解析租户/门店并做跨 scope 校验）。
     *
     * <p>必须跳过租户行拦截器：渠道回调只带订单号、不带支付域租户上下文，本查询的职责恰恰是
     * **先解析出订单租户**；随后的所有读写都会切换到该租户上下文（见 ChannelSettlementApplicationService）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT o.id AS order_id, o.tenant_id, o.store_id, o.trade_status,
                   o.total_amount, o.paid_amount, o.refundable_amount, o.refund_status, o.currency_code
              FROM ord_order o
             WHERE o.id = #{orderId}
            """)
    ChannelOrderSnapshotPo selectOrderSnapshotById(@Param("orderId") Long orderId);

    /** 在租户内只读订单结算口径（加锁落账后回读最新结果）。 */
    @Select("""
            SELECT o.id AS order_id, o.tenant_id, o.store_id, o.trade_status,
                   o.total_amount, o.paid_amount, o.refundable_amount, o.refund_status, o.currency_code
              FROM ord_order o
             WHERE o.tenant_id = #{tenantId} AND o.id = #{orderId}
            """)
    ChannelOrderSnapshotPo selectOrderSnapshot(@Param("tenantId") Long tenantId,
                                               @Param("orderId") Long orderId);
}
