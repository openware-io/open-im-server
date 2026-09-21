package com.gvchat.platform.order.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.order.infra.persistence.mapper.OrderItemMapper;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 结算：服务端按明细快照计算金额，客户端只提交选择项 + expectedVersion，不传金额。
 * 首发顺序：原价合计 → 优惠（券/折扣）→ 积分抵扣 → 储值币/代币 → 现金补差额（优惠/积分/储值在 E5/E6 接入，此处先算原价合计）。
 */
@Service
public class SettlementApplicationService {
    private final OrderMapper orderMapper;
    private final AuditClient auditClient;
    private final OrderAmountApplicationService orderAmounts;

    public SettlementApplicationService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                                        AuditClient auditClient, OrderAmountApplicationService orderAmounts) {
        this.orderMapper = orderMapper;
        this.auditClient = auditClient;
        this.orderAmounts = orderAmounts;
    }

    /** 结算：汇总明细（含 KTV 服务人员费作为 item 落入 order_item 后），固化订单金额快照。 */
    @Transactional
    public OrderPo settle(Long orderId, int expectedVersion) {
        OrderPo order = orderMapper.selectById(orderId);
        try {
            if (order == null) {
                if (orderMapper.selectTenantIdById(orderId) != null) {
                    throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的订单");
                }
                throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
            }
            if (!order.getVersion().equals(expectedVersion)) throw new BusinessException("ORDER_VERSION_CONFLICT", "订单版本冲突，请重试");
            if (!"WAITING_SETTLEMENT".equals(order.getStatus())) throw new BusinessException("ORDER_STATUS_INVALID", "仅待结算订单可结算");

            // 与账单同一口径：只汇总已生效（ACTIVE）明细，待确认/已拒绝不计入应付。
            orderAmounts.recalculate(order);
            order.setStatus("WAITING_PAYMENT");
            order.setVersion(order.getVersion() + 1);
            order.setUpdatedAt(LocalDateTime.now());
            orderMapper.updateById(order);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(order.getTenantId())
                    .storeId(order.getStoreId())
                    .action("order.settle")
                    .actionLabel("结台结算")
                    .resourceType("ord_order")
                    .resourceId(String.valueOf(order.getId()))
                    .resourceName(order.getOrderNo())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"totalAmount\":" + order.getTotalAmount() + ",\"discount\":" + order.getDiscountAmount()
                            + ",\"tax\":" + order.getTaxAmount() + "}")
                    .build());
            return order;
        } catch (RuntimeException failure) {
            // 结算失败留痕（订单不存在/跨租户/版本冲突/状态不符/复算失败）：与成功同码 order.settle。
            // 审计只 WARN、异常原样抛出；detail 只放订单号，不含金额（失败详情不入敏感信息）。
            TenantContext context = TenantContextHolder.get();
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(order == null ? (context == null ? null : context.tenantId()) : order.getTenantId())
                    .storeId(order == null ? (context == null ? null : context.storeId()) : order.getStoreId())
                    .action("order.settle")
                    .actionLabel("结台结算")
                    .resourceType("ord_order")
                    .resourceId(orderId == null ? null : String.valueOf(orderId))
                    .resourceName(order == null ? null : order.getOrderNo())
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"orderId\":" + orderId + "}")
                    .build());
            throw failure;
        }
    }
}
