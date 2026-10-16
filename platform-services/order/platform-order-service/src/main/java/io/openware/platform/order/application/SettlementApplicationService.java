package io.openware.platform.order.application;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.order.domain.order.model.Order;
import io.openware.platform.order.domain.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算：服务端按明细快照计算金额，客户端只提交选择项 + expectedVersion，不传金额。
 * 首发顺序：原价合计 → 优惠（券/折扣）→ 积分抵扣 → 储值币/代币 → 现金补差额（优惠/积分/储值在 E5/E6 接入，此处先算原价合计）。
 *
 * <p><b>领域模型不可变</b>：金额重算与状态转换都返回新实例，本方法用返回值继续（旧实例不再被改写），
 * 因此「旧实例覆盖新金额」这类缺陷在类型层面就不可能发生。
 */
@Service
public class SettlementApplicationService {
    private final OrderRepository orderRepository;
    private final AuditClient auditClient;
    private final OrderAmountApplicationService orderAmounts;

    public SettlementApplicationService(OrderRepository orderRepository, AuditClient auditClient,
                                        OrderAmountApplicationService orderAmounts) {
        this.orderRepository = orderRepository;
        this.auditClient = auditClient;
        this.orderAmounts = orderAmounts;
    }

    /** 结算：汇总明细（含 KTV 服务人员费作为 item 落入 order_item 后），固化订单金额快照。 */
    @Transactional
    public Order settle(Long orderId, int expectedVersion) {
        Order order = orderRepository.findById(orderId).orElse(null);
        try {
            if (order == null) {
                if (orderRepository.findTenantIdById(orderId).isPresent()) {
                    throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的订单");
                }
                throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
            }
            if (order.currentVersion() != expectedVersion) {
                throw new BusinessException("ORDER_VERSION_CONFLICT", "订单版本冲突，请重试");
            }
            if (!"WAITING_SETTLEMENT".equals(order.status())) {
                throw new BusinessException("ORDER_STATUS_INVALID", "仅待结算订单可结算");
            }

            // 与账单同一口径：只汇总已生效（ACTIVE）明细，待确认/已拒绝不计入应付。
            order = orderAmounts.recalculate(order).withStatus("WAITING_PAYMENT");
            // version 由 SQL 自增，Java 侧不再预先 +1（预先加一会在并发窗口内写回过期版本号）。
            //
            // 上面那句 currentVersion 比对只是**快失败**提示，不是并发保护：
            // 两个并发结算都会读到同一个 version 并通过这句比对。真正的判定是下面这条带 version 条件的
            // UPDATE —— ord_order 有 version 列，但 OrderPo 没有 @Version、工程里也没有乐观锁拦截器，
            // 所以 updateById 不会附加版本条件，受影响 0 行必须转成冲突而不是当成成功。
            if (!orderRepository.updateAmountsAndStatusWithVersion(order, expectedVersion)) {
                throw new BusinessException("ORDER_VERSION_CONFLICT", "订单版本冲突，请重试");
            }
            order = order.withVersion(expectedVersion + 1);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(order.tenantId())
                    .storeId(order.storeId())
                    .action("order.settle")
                    .actionLabel("结台结算")
                    .resourceType("ord_order")
                    .resourceId(String.valueOf(order.id()))
                    .resourceName(order.orderNo())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"totalAmount\":" + order.totalAmount() + ",\"discount\":" + order.discountAmount()
                            + ",\"tax\":" + order.taxAmount() + "}")
                    .build());
            return order;
        } catch (RuntimeException failure) {
            // 结算失败留痕（订单不存在/跨租户/版本冲突/状态不符/复算失败）：与成功同码 order.settle。
            // 审计只 WARN、异常原样抛出；detail 只放订单号，不含金额（失败详情不入敏感信息）。
            TenantContext context = TenantContextHolder.get();
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(order == null ? (context == null ? null : context.tenantId()) : order.tenantId())
                    .storeId(order == null ? (context == null ? null : context.storeId()) : order.storeId())
                    .action("order.settle")
                    .actionLabel("结台结算")
                    .resourceType("ord_order")
                    .resourceId(orderId == null ? null : String.valueOf(orderId))
                    .resourceName(order == null ? null : order.orderNo())
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"orderId\":" + orderId + "}")
                    .build());
            throw failure;
        }
    }
}
