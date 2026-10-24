package io.openware.common.payment.application;

import io.openware.common.payment.domain.channel.repository.Persisted;
import io.openware.common.payment.domain.refund.model.Refund;
import io.openware.common.payment.domain.refund.repository.RefundRepository;
import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import io.openware.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 退款审批状态机：PENDING → APPROVED/REJECTED → REFUNDED（KTV_BUSINESS_01 §7.5 / §10.2）。
 * 收银员只能申请（PENDING）；店长/财务审批（APPROVED/REJECTED，权限 payment.refund.approve）；
 * 审批通过后财务登记线下退款（REFUNDED，权限 payment.refund.offline）。
 * 申请人不能自批由上层权限码 + 审计操作者信息承载（骨架）。
 *
 * <p><b>并发</b>：每次状态迁移都是一条**状态守卫的条件更新**（{@code WHERE id = ? AND status = ?}）。
 * {@code pay_refund} 没有版本列，状态本身就是唯一的并发判据；"读出来判状态再无条件写回"会让两个并发
 * 审批者双双通过判断——对退款而言就是同一笔申请被批准两次。
 */
@Service
public class RefundApplicationService {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    private final RefundRepository refundRepository;
    private final AuditClient auditClient;
    private final OrderBillingRepository orderBillingRepository;
    private final PaymentRuleApplicationService paymentRuleService;

    public RefundApplicationService(RefundRepository refundRepository, AuditClient auditClient,
                                    OrderBillingRepository orderBillingRepository) {
        this(refundRepository, auditClient, orderBillingRepository, null);
    }

    @Autowired
    public RefundApplicationService(RefundRepository refundRepository, AuditClient auditClient,
                                    OrderBillingRepository orderBillingRepository,
                                    PaymentRuleApplicationService paymentRuleService) {
        this.refundRepository = refundRepository;
        this.auditClient = auditClient;
        this.orderBillingRepository = orderBillingRepository;
        this.paymentRuleService = paymentRuleService;
    }

    /** 兼容既有装配/单测：无账单查询时退款币种回退当时租户币种（缺省 USD）。 */
    public RefundApplicationService(RefundRepository refundRepository, AuditClient auditClient) {
        this(refundRepository, auditClient, null);
    }

    /**
     * 收银员申请退款：创建 PENDING 记录（幂等唯一键 uk_pay_refund_tenant_req 兜底）并固化门店归因。
     * 门店 ID 来自调用方已校验的经营上下文，不允许在支付域跨库猜测门店。
     */
    @Transactional
    public RefundDto requestRefund(Long tenantId, Long storeId, Long orderId, BigDecimal amount, String reason,
                                   Long requestedBy) {
        try {
            TenantContext context = TenantContextHolder.get();
            // 币种快照（16_CURRENCY_CONVENTIONS §5/§6.3）：退款必须退**原币种**，与申请金额同事务落库。
            String currencyCode = resolveRefundCurrency(tenantId, orderId);
            String requestId = UUID.randomUUID().toString();
            Refund draft = new Refund(null, tenantId, storeId,
                    context == null ? null : context.businessType(), orderId, null, requestId, amount, null,
                    currencyCode, null, STATUS_PENDING, reason, requestedBy, null);
            Persisted<Refund> saved =
                    refundRepository.insert(draft, LocalDateTime.now());
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .operatorId(requestedBy)
                    .action("payment.refund.request")
                    .actionLabel("退款申请")
                    .resourceType("refund")
                    .resourceId(saved.aggregate().requestId())
                    .idempotencyKey(saved.aggregate().requestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + orderId + ",\"amount\":\"" + amount + "\",\"currencyCode\":\""
                            + saved.aggregate().currencyCode() + "\"}")
                    .build());
            return RefundDto.from(saved);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.request", "退款申请", tenantId, null, orderId, failure);
            throw failure;
        }
    }

    /**
     * 退款币种：优先取**原订单的币种快照**（退原币种），订单读不到（不存在 / 未注入账单查询）时
     * 回退当时租户币种（缺省 USD）。跨币种退款是合规禁区，因此这里不做任何汇率换算。
     */
    private String resolveRefundCurrency(Long tenantId, Long orderId) {
        if (orderBillingRepository != null && tenantId != null && orderId != null) {
            // 只读、不加锁：建退款单不需要锁住订单主行，否则退款申请会与收款互相阻塞。
            var orderCurrency = orderBillingRepository.findCurrencyCode(tenantId, orderId);
            if (orderCurrency.isPresent() && !orderCurrency.get().isBlank()) {
                return Currency.parse(orderCurrency.get()).code();
            }
        }
        return CurrencyResolver.currentCode();
    }

    /** 店长/财务审批通过：PENDING → APPROVED（批准金额不可超申请金额）。 */
    @Transactional
    public RefundDto approveRefund(Long refundId, BigDecimal approvedAmount, Long approvedBy) {
        try {
            Persisted<Refund> row = requireRefund(refundId);
            Refund refund = row.aggregate();
            requireStatus(refund, STATUS_PENDING, "approve");
            if (approvedAmount == null || approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("REFUND_AMOUNT_INVALID");
            }
            if (refund.requestedAmount() != null && approvedAmount.compareTo(refund.requestedAmount()) > 0) {
                throw new IllegalStateException("REFUND_AMOUNT_EXCEEDS_REQUEST");
            }
            if (orderBillingRepository != null && refund.orderId() != null) {
                var order = orderBillingRepository.findForUpdate(refund.tenantId(), refund.orderId()).orElse(null);
                if (order != null && approvedAmount.compareTo(order.paidOrZero()) > 0) {
                    throw new ApiException(422, "REFUND_EXCEEDS_PAID", "退款金额超过订单可退金额");
                }
            }
            Refund approved = withStatus(refund, STATUS_APPROVED, approvedAmount, approvedBy, null);
            Persisted<Refund> approvedRow =
                    transactionGuarded(approved, STATUS_PENDING, "approve");
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(refund.tenantId())
                    .operatorId(approvedBy)
                    .action("payment.refund.approve")
                    .actionLabel("退款审批通过")
                    .resourceType("refund")
                    .resourceId(refund.requestId())
                    .idempotencyKey(refund.requestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"approvedAmount\":\"" + approvedAmount + "\"}")
                    .build());
            return RefundDto.from(approvedRow);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.approve", "退款审批通过", null, refundId, null, failure);
            throw failure;
        }
    }

    /** 店长/财务驳回：PENDING → REJECTED。 */
    @Transactional
    public RefundDto rejectRefund(Long refundId, Long rejectedBy) {
        try {
            Persisted<Refund> row = requireRefund(refundId);
            Refund refund = row.aggregate();
            requireStatus(refund, STATUS_PENDING, "reject");
            Refund rejected = withStatus(refund, STATUS_REJECTED, refund.approvedAmount(), rejectedBy, null);
            Persisted<Refund> rejectedRow = transactionGuarded(rejected, STATUS_PENDING, "reject");
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(refund.tenantId())
                    .operatorId(rejectedBy)
                    .action("payment.refund.reject")
                    .actionLabel("退款审批驳回")
                    .resourceType("refund")
                    .resourceId(refund.requestId())
                    .idempotencyKey(refund.requestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .build());
            return RefundDto.from(rejectedRow);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.reject", "退款审批驳回", null, refundId, null, failure);
            throw failure;
        }
    }

    /** 财务登记线下退款：APPROVED → REFUNDED。 */
    @Transactional
    public RefundDto markRefunded(Long refundId, String providerRefundNo, Long operatorId) {
        try {
            Persisted<Refund> row = requireRefund(refundId);
            Refund refund = row.aggregate();
            requireStatus(refund, STATUS_APPROVED, "refund");
            if (paymentRuleService != null) {
                PaymentRuleApplicationService.RefundRuleView rule = paymentRuleService.resolveRefund(
                        refund.tenantId(), refund.storeId(), refund.businessType());
                if (!rule.offlineRefundEnabled()) {
                    throw new ApiException(422, "OFFLINE_REFUND_DISABLED", "当前业态或门店不允许线下退款");
                }
            }
            Refund refunded = withStatus(refund, STATUS_REFUNDED, refund.approvedAmount(), refund.approvedBy(),
                    providerRefundNo);
            Persisted<Refund> refundedRow = transactionGuarded(refunded, STATUS_APPROVED, "refund");
            if (orderBillingRepository != null && refund.orderId() != null
                    && refund.approvedAmount() != null) {
                if (!orderBillingRepository.markRefunded(refund.tenantId(), refund.orderId(), refund.approvedAmount())) {
                    throw new IllegalStateException("REFUND_ORDER_UPDATE_FAILED");
                }
            }
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(refund.tenantId())
                    .operatorId(operatorId)
                    .action("payment.refund.offline")
                    .actionLabel("线下退款登记")
                    .resourceType("refund")
                    .resourceId(refund.requestId())
                    .idempotencyKey(refund.requestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"providerRefundNo\":\""
                            + (providerRefundNo == null ? "" : providerRefundNo) + "\"}")
                    .build());
            return RefundDto.from(refundedRow);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.offline", "线下退款登记", null, refundId, null, failure);
            throw failure;
        }
    }

    /**
     * 执行状态迁移（状态守卫的 CAS）。
     *
     * <p>受影响 0 行 = 状态已被他人改变。这里报 {@code REFUND_STATE_INVALID} 而不是冲突码：
     * 对调用方而言语义相同（这次迁移没生效、钱没退出去），而错误里的状态说明更有助于排查。
     */
    private Persisted<Refund> transactionGuarded(Refund target, String expectedStatus, String action) {
        return refundRepository.updateStatusGuarded(target, expectedStatus, LocalDateTime.now())
                .orElseThrow(() -> new IllegalStateException(
                        "REFUND_STATE_INVALID: cannot " + action + " refund in state " + expectedStatus));
    }

    /** 生成迁移后的退款单快照（不可变模型按需替换字段）。 */
    private static Refund withStatus(Refund refund, String status, BigDecimal approvedAmount,
                                     Long approvedBy, String providerRefundNo) {
        return new Refund(refund.id(), refund.tenantId(), refund.storeId(), refund.businessType(),
                refund.orderId(), refund.paymentTransactionId(), refund.requestId(),
                refund.requestedAmount(), approvedAmount, refund.currencyCode(),
                providerRefundNo == null ? refund.providerRefundNo() : providerRefundNo, status,
                refund.reason(), refund.requestedBy(),
                approvedBy == null ? refund.approvedBy() : approvedBy);
    }

    /**
     * 退款写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode
     * （本服务抛 {@link IllegalStateException}，取类名；有稳定业务码时优先取业务码，并按列宽 64 截断）。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 失败路径不再回查退款单（避免二次读库再抛异常掩盖原始业务异常）；租户缺省由
     * {@link AuditClient} 从当前请求的租户上下文补全。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；
     * detail 只放退款单/订单 ID，<b>不含</b>退款金额、线下退款单号与申请人信息。
     */
    private void recordFailure(String action, String actionLabel, Long tenantId, Long refundId, Long orderId,
                               RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("refund")
                .resourceId(refundId == null ? null : String.valueOf(refundId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"refundId\":" + refundId + ",\"orderId\":" + orderId + "}")
                .build());
    }

    private Persisted<Refund> requireRefund(Long refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("REFUND_NOT_FOUND"));
    }

    private void requireStatus(Refund refund, String expected, String action) {
        if (!expected.equals(refund.status())) {
            throw new IllegalStateException(
                    "REFUND_STATE_INVALID: cannot " + action + " refund in state " + refund.status());
        }
    }
}
