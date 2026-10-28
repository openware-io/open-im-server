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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.UUID;
import io.openware.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

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

    /** {@code pay_refund.request_id} 列宽（超长幂等键按摘要截断到该长度内）。 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

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
     *
     * <p><b>重复提交语义</b>（收口 must-fix：重复申请不得新建第二条 refund）：
     * <ul>
     *   <li>带 {@code Idempotency-Key}：键映射为 {@code request_id} 并受租户内唯一键约束；同键重放直接返回
     *       既有行（同 id、不新建；即使该退款已完成也回放 200），同键不同 payload → 409
     *       {@code IDEMPOTENCY_KEY_REUSED}。</li>
     *   <li>同订单 + 同申请金额已存在 PENDING/APPROVED/REFUNDED 退款单（即不是 REJECTED）→ 409
     *       {@code REFUND_DUPLICATE}，不新建第二条。REFUNDED 命中的是"同一笔钱已退过又申请退一次"。</li>
     *   <li>申请金额超过订单当前已收金额（已扣除已完成退款）→ 422 {@code REFUND_EXCEEDS_PAID}。</li>
     * </ul>
     */
    @Transactional
    public RefundDto requestRefund(Long tenantId, Long storeId, Long orderId, BigDecimal amount, String reason,
                                   Long requestedBy) {
        return requestRefund(tenantId, storeId, orderId, amount, reason, requestedBy, null);
    }

    /** 带幂等键的退款申请（{@code Idempotency-Key} 头；空白视为未提供）。 */
    @Transactional
    public RefundDto requestRefund(Long tenantId, Long storeId, Long orderId, BigDecimal amount, String reason,
                                   Long requestedBy, String idempotencyKey) {
        try {
            TenantContext context = TenantContextHolder.get();
            requireOrderStore(tenantId, storeId, orderId);
            requireRefundAmount(amount);
            String requestId = resolveRequestId(idempotencyKey);
            if (requestId != null) {
                // 同键重放优先于金额/重复校验：已完成（REFUNDED）的退款也应返回 200 + 同 id，而不是 409/422。
                Persisted<Refund> existing = refundRepository.findByRequestId(tenantId, requestId).orElse(null);
                if (existing != null) {
                    return replayExisting(existing, orderId, amount);
                }
            }
            // 同订单同金额去重（PENDING/APPROVED/REFUNDED；REJECTED 除外）：带不带幂等键都适用，
            // 否则"已退过的钱"或"审批中的同额申请"会被重复建单。
            refundRepository.findDuplicateCandidate(tenantId, orderId, amount)
                    .ifPresent(duplicate -> {
                        throw new ApiException(409, "REFUND_DUPLICATE",
                                "订单已有同金额的退款申请或已完成退款，请勿重复提交");
                    });
            // 申请金额不得超过订单当前可退（已收 − 已完成退款）；该检查放在重复判定之后，
            // 让"全额退款后重复申请"稳定返回 409 REFUND_DUPLICATE（E2E 硬断言），而不是 422。
            requireRefundableAmount(tenantId, orderId, amount);
            // 币种快照（16_CURRENCY_CONVENTIONS §5/§6.3）：退款必须退**原币种**，与申请金额同事务落库。
            String currencyCode = resolveRefundCurrency(tenantId, orderId);
            Refund draft = new Refund(null, tenantId, storeId,
                    context == null ? null : context.businessType(), orderId, null,
                    requestId == null ? UUID.randomUUID().toString() : requestId, amount, null,
                    currencyCode, null, STATUS_PENDING, reason, requestedBy, null);
            Persisted<Refund> saved;
            try {
                saved = refundRepository.insert(draft, LocalDateTime.now());
            } catch (DuplicateKeyException concurrent) {
                // 并发同键：由 uk_pay_refund_tenant_req 兜底，回读后按幂等回放；不同 payload 转 409 冲突。
                if (requestId == null) {
                    throw concurrent;
                }
                Persisted<Refund> winner = refundRepository.findByRequestId(tenantId, requestId)
                        .orElseThrow(() -> concurrent);
                return replayExisting(winner, orderId, amount);
            }
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
     * 幂等键重放：同订单 + 同申请金额才返回既有行；否则说明调用方复用了键但换了 payload，报 409。
     * 返回既有行时**不新增 pay_refund、不重新留痕**。
     */
    private RefundDto replayExisting(Persisted<Refund> existing, Long orderId, BigDecimal amount) {
        Refund refund = existing.aggregate();
        boolean sameOrder = refund.orderId() != null && refund.orderId().equals(orderId);
        boolean sameAmount = refund.requestedAmount() != null && amount != null
                && refund.requestedAmount().compareTo(amount) == 0;
        if (!sameOrder || !sameAmount) {
            throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED",
                    "幂等键已用于另一笔退款申请，请更换 Idempotency-Key");
        }
        return RefundDto.from(existing);
    }

    /** {@code pay_refund.request_id} 为 varchar(64)：短键直接作为申请号（可读可追），超长键按 SHA-256 摘要截断。 */
    private static String resolveRequestId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.length() <= MAX_REQUEST_ID_LENGTH) {
            return key;
        }
        return "IDEM-" + sha256Hex(key).substring(0, 40);
    }

    private static void requireRefundAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(422, "REFUND_AMOUNT_INVALID", "退款金额必须为正数");
        }
    }

    /**
     * 申请阶段的可退上限预检：申请金额不得超过订单**当前已收金额**（已扣除已完成退款回冲）。
     *
     * <p>冻结前 E2E 场景：订单已全额退款（{@code paid_amount=0}）后重复提交同额退款申请，此前会新建
     * 第二条 PENDING 行（到审批阶段才失败）。这里在同事务内用行锁读订单已收金额，
     * 超限直接 422 {@code REFUND_EXCEEDS_PAID}，DB 不新增行。
     *
     * <p>为什么用 {@code findForUpdate}：本次申请与并发退款/收款需要基于同一份已收金额判定，
     * 锁持有到事务提交（只包住一次插入，时间很短）。
     */
    private void requireRefundableAmount(Long tenantId, Long orderId, BigDecimal amount) {
        if (orderBillingRepository == null || tenantId == null || orderId == null) {
            return;
        }
        var order = orderBillingRepository.findForUpdate(tenantId, orderId).orElse(null);
        if (order == null) {
            return;
        }
        if (amount.compareTo(order.paidOrZero()) > 0) {
            throw new ApiException(422, "REFUND_EXCEEDS_PAID", "退款金额超过订单当前可退金额");
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash the idempotency key", e);
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
            requireRefundStore(refund);
            requireStatus(refund, STATUS_PENDING, "approve");
            if (approvedAmount == null || approvedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException(422, "REFUND_AMOUNT_INVALID", "批准金额必须为正数");
            }
            if (refund.requestedAmount() != null && approvedAmount.compareTo(refund.requestedAmount()) > 0) {
                throw new ApiException(422, "REFUND_AMOUNT_EXCEEDS_REQUEST", "批准金额不得超过申请金额");
            }
            if (orderBillingRepository != null && refund.orderId() != null) {
                var order = orderBillingRepository.findForUpdate(refund.tenantId(), refund.orderId()).orElse(null);
                java.math.BigDecimal reserved = refundRepository.sumActiveAmount(refund.tenantId(),
                        refund.orderId(), refund.id());
                if (order != null && approvedAmount.add(reserved).compareTo(order.paidOrZero()) > 0) {
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
            requireRefundStore(refund);
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
            requireRefundStore(refund);
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
                    throw new ApiException(409, "REFUND_ORDER_CONFLICT",
                            "订单退款落账冲突，请核对订单当前收款/退款状态");
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
     * <p>受影响 0 行 = 状态已被他人改变（并发抢先 / 重复提交）。这是**业务冲突**，必须返回 409
     * {@code REFUND_STATE_INVALID} 而不是 500：重复登记线下退款时调用方需要可判定的 4xx，
     * 且不得把"未生效"当成"已退出去"。
     */
    private Persisted<Refund> transactionGuarded(Refund target, String expectedStatus, String action) {
        return refundRepository.updateStatusGuarded(target, expectedStatus, LocalDateTime.now())
                .orElseThrow(() -> new ApiException(409, "REFUND_STATE_INVALID",
                        "退款状态已变更，无法执行 " + action + "（期望 " + expectedStatus + "）"));
    }

    private void requireOrderStore(Long tenantId, Long storeId, Long orderId) {
        if (orderBillingRepository == null) {
            return;
        }
        Long orderStore = orderBillingRepository.findStoreId(tenantId, orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "订单不属于当前门店"));
        if (storeId == null || !storeId.equals(orderStore)) {
            throw new ApiException(404, "ORDER_NOT_FOUND", "订单不属于当前门店");
        }
    }

    private void requireRefundStore(Refund refund) {
        TenantContext context = TenantContextHolder.get();
        if (context == null && orderBillingRepository != null) {
            throw new ApiException(404, "REFUND_NOT_FOUND", "缺少门店上下文");
        }
        if (context != null && !context.platformScope()) {
            if (context.storeId() == null || !context.storeId().equals(refund.storeId())) {
                throw new ApiException(404, "REFUND_NOT_FOUND", "退款单不属于当前门店");
            }
        }
        if (orderBillingRepository != null && refund.orderId() != null) {
            Long orderStore = orderBillingRepository.findStoreId(refund.tenantId(), refund.orderId()).orElse(null);
            if (orderStore == null || !orderStore.equals(refund.storeId())) {
                throw new ApiException(404, "REFUND_NOT_FOUND", "退款单与订单门店不一致");
            }
        }
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
     * （本服务抛 {@link ApiException}，优先取稳定业务码，例如 {@code REFUND_STATE_INVALID}；
     * 无业务码的框架异常退回异常类名，并按列宽 64 截断）。
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
                .orElseThrow(() -> new ApiException(404, "REFUND_NOT_FOUND", "退款单不存在: " + refundId));
    }

    private void requireStatus(Refund refund, String expected, String action) {
        if (!expected.equals(refund.status())) {
            throw new ApiException(409, "REFUND_STATE_INVALID",
                    "退款状态不允许该操作: 期望 " + expected + "，实际 " + refund.status());
        }
    }
}
