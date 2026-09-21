package com.gvchat.common.payment.application;

import com.gvchat.common.payment.infra.persistence.mapper.OrderBillingMapper;
import com.gvchat.common.payment.infra.persistence.mapper.RefundMapper;
import com.gvchat.common.payment.infra.persistence.po.RefundPo;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 退款审批状态机：PENDING → APPROVED/REJECTED → REFUNDED（KTV_BUSINESS_01 §7.5 / §10.2）。
 * 收银员只能申请（PENDING）；店长/财务审批（APPROVED/REJECTED，权限 payment.refund.approve）；
 * 审批通过后财务登记线下退款（REFUNDED，权限 payment.refund.offline）。
 * 申请人不能自批由上层权限码 + 审计操作者信息承载（骨架）。
 */
@Service
public class RefundApplicationService {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    private final RefundMapper refundMapper;
    private final AuditClient auditClient;
    private final OrderBillingMapper orderBillingMapper;

    @Autowired
    public RefundApplicationService(RefundMapper refundMapper, AuditClient auditClient,
                                    OrderBillingMapper orderBillingMapper) {
        this.refundMapper = refundMapper;
        this.auditClient = auditClient;
        this.orderBillingMapper = orderBillingMapper;
    }

    /** 兼容既有装配/单测：无账单查询时退款币种回退当时租户币种（缺省 USD）。 */
    public RefundApplicationService(RefundMapper refundMapper, AuditClient auditClient) {
        this(refundMapper, auditClient, null);
    }

    /** 收银员申请退款：创建 PENDING 记录（幂等唯一键 uk_pay_refund_tenant_req 兜底）。 */
    @Transactional
    public RefundDto requestRefund(Long tenantId, Long orderId, BigDecimal amount, String reason, Long requestedBy) {
        try {
            RefundPo po = new RefundPo();
            po.setTenantId(tenantId); po.setOrderId(orderId);
            po.setRequestId(UUID.randomUUID().toString());
            po.setRequestedAmount(amount); po.setReason(reason); po.setRequestedBy(requestedBy);
            // 币种快照（16_CURRENCY_CONVENTIONS §5/§6.3）：退款必须退**原币种**，与申请金额同事务落库。
            po.setCurrencyCode(resolveRefundCurrency(tenantId, orderId));
            po.setStatus(STATUS_PENDING); po.setCreatedAt(LocalDateTime.now()); po.setUpdatedAt(LocalDateTime.now());
            refundMapper.insert(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .operatorId(requestedBy)
                    .action("payment.refund.request")
                    .actionLabel("退款申请")
                    .resourceType("refund")
                    .resourceId(po.getRequestId())
                    .idempotencyKey(po.getRequestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + orderId + ",\"amount\":\"" + amount + "\",\"currencyCode\":\""
                            + po.getCurrencyCode() + "\"}")
                    .build());
            return RefundDto.from(po);
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
        if (orderBillingMapper != null && tenantId != null && orderId != null) {
            String orderCurrency = orderBillingMapper.selectCurrencyCode(tenantId, orderId);
            if (orderCurrency != null && !orderCurrency.isBlank()) {
                return Currency.parse(orderCurrency).code();
            }
        }
        return CurrencyResolver.currentCode();
    }

    /** 店长/财务审批通过：PENDING → APPROVED（批准金额不可超申请金额）。 */
    @Transactional
    public RefundDto approveRefund(Long refundId, BigDecimal approvedAmount, Long approvedBy) {
        try {
            RefundPo po = requireRefund(refundId);
            requireStatus(po, STATUS_PENDING, "approve");
            if (approvedAmount == null || approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("REFUND_AMOUNT_INVALID");
            }
            if (po.getRequestedAmount() != null && approvedAmount.compareTo(po.getRequestedAmount()) > 0) {
                throw new IllegalStateException("REFUND_AMOUNT_EXCEEDS_REQUEST");
            }
            po.setStatus(STATUS_APPROVED);
            po.setApprovedAmount(approvedAmount);
            po.setApprovedBy(approvedBy);
            po.setUpdatedAt(LocalDateTime.now());
            refundMapper.updateById(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .operatorId(approvedBy)
                    .action("payment.refund.approve")
                    .actionLabel("退款审批通过")
                    .resourceType("refund")
                    .resourceId(po.getRequestId())
                    .idempotencyKey(po.getRequestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"approvedAmount\":\"" + approvedAmount + "\"}")
                    .build());
            return RefundDto.from(po);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.approve", "退款审批通过", null, refundId, null, failure);
            throw failure;
        }
    }

    /** 店长/财务驳回：PENDING → REJECTED。 */
    @Transactional
    public RefundDto rejectRefund(Long refundId, Long rejectedBy) {
        try {
            RefundPo po = requireRefund(refundId);
            requireStatus(po, STATUS_PENDING, "reject");
            po.setStatus(STATUS_REJECTED);
            po.setApprovedBy(rejectedBy);
            po.setUpdatedAt(LocalDateTime.now());
            refundMapper.updateById(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .operatorId(rejectedBy)
                    .action("payment.refund.reject")
                    .actionLabel("退款审批驳回")
                    .resourceType("refund")
                    .resourceId(po.getRequestId())
                    .idempotencyKey(po.getRequestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .build());
            return RefundDto.from(po);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.reject", "退款审批驳回", null, refundId, null, failure);
            throw failure;
        }
    }

    /** 财务登记线下退款：APPROVED → REFUNDED。 */
    @Transactional
    public RefundDto markRefunded(Long refundId, String providerRefundNo, Long operatorId) {
        try {
            RefundPo po = requireRefund(refundId);
            requireStatus(po, STATUS_APPROVED, "refund");
            po.setStatus(STATUS_REFUNDED);
            po.setProviderRefundNo(providerRefundNo);
            po.setUpdatedAt(LocalDateTime.now());
            refundMapper.updateById(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .operatorId(operatorId)
                    .action("payment.refund.offline")
                    .actionLabel("线下退款登记")
                    .resourceType("refund")
                    .resourceId(po.getRequestId())
                    .idempotencyKey(po.getRequestId())
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"providerRefundNo\":\"" + (providerRefundNo == null ? "" : providerRefundNo) + "\"}")
                    .build());
            return RefundDto.from(po);
        } catch (RuntimeException failure) {
            recordFailure("payment.refund.offline", "线下退款登记", null, refundId, null, failure);
            throw failure;
        }
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

    private RefundPo requireRefund(Long refundId) {
        RefundPo po = refundMapper.selectById(refundId);
        if (po == null) {
            throw new IllegalStateException("REFUND_NOT_FOUND");
        }
        return po;
    }

    private void requireStatus(RefundPo po, String expected, String action) {
        if (!expected.equals(po.getStatus())) {
            throw new IllegalStateException(
                    "REFUND_STATE_INVALID: cannot " + action + " refund in state " + po.getStatus());
        }
    }
}
