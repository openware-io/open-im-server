package com.gvchat.platform.order.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.platform.order.infra.client.PaymentCollectedClient;
import com.gvchat.platform.order.infra.persistence.mapper.OrderMapper;
import com.gvchat.platform.order.infra.persistence.po.KtvSessionPo;
import com.gvchat.platform.order.infra.persistence.po.OrderPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * 订单取消应用服务：运营后台「取消订单」（{@code POST /business/orders/{id}/cancel}）与既有
 * 「作废订单」（{@code POST /business/orders/{id}/void}）**共用同一套状态校验、已收款拦截与包厢释放实现**，
 * 只在「审计动作码」与两处兼容语义上分叉，避免两条路径规则漂移。
 *
 * <p>规则（两条路径一致）：
 * <ol>
 *   <li>订单不存在 → 404 {@code ORDER_NOT_FOUND}；跨租户 → 403 {@code TENANT_SCOPE_DENIED}；</li>
 *   <li>已完成（COMPLETED）/已退款（REFUNDED、PARTIAL_REFUNDED）/已取消（CANCELLED）
 *       → 409 {@code ORDER_STATUS_INVALID}：只有未完成订单可取消；</li>
 *   <li>已收款拦截：{@code paid_amount > 0} 或 payment 域存在成功收款流水
 *       → 409 {@code ORDER_HAS_PAYMENT_REFUND_FIRST}（「该订单已有收款，请先退款后再取消」），
 *       绝不静默吞掉已收金额；</li>
 *   <li>释放包厢占用：订单存在活动 KTV 会话时，同一事务内取消会话并释放占用
 *       （{@link KtvSessionApplicationService#cancelByOrder}，不另写占用表操作），
 *       sessionId/resourceId 记入审计；</li>
 *   <li>置 VOIDED + cancelled_at，审计 detailJson 含
 *       beforeStatus/afterStatus/reason/releasedSessionId/releasedResourceId。</li>
 * </ol>
 *
 * <p>两处分叉：
 * <ul>
 *   <li>取消（{@link CancelKind#CANCEL}）：审计码 {@code order.cancel}，**原因必填**
 *       （400 {@code CANCEL_REASON_REQUIRED}），重复取消同一订单**幂等**返回既有结果且不重复留痕；</li>
 *   <li>作废（{@link CancelKind#VOID}）：审计码 {@code order.void}，原因保持**选填**
 *       （既有后台按钮与调用方兼容），已作废仍按 409 拒绝（保留既有对外行为）。</li>
 * </ul>
 */
@Service
public class OrderCancellationApplicationService {

    /** 已收款拦截的错误码与提示（对外契约，前端按码提示「先退款」）。 */
    public static final String PAYMENT_REFUND_FIRST_CODE = "ORDER_HAS_PAYMENT_REFUND_FIRST";
    public static final String PAYMENT_REFUND_FIRST_MESSAGE = "该订单已有收款，请先退款后再取消";

    /** 终态：只有未完成订单可取消（VOIDED 单独处理，见 {@link #apply}）。 */
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "CANCELLED", "REFUNDED", "PARTIAL_REFUNDED");

    /** 取消动作类型：决定审计动作码、原因是否必填、以及「已作废」时是否幂等返回。 */
    public enum CancelKind {
        /** 运营取消订单：审计 order.cancel，原因必填，重复取消幂等。 */
        CANCEL("order.cancel", "取消订单", "取消", "order-cancel", true, true),
        /** 既有作废订单：审计 order.void，原因选填，已作废仍 409（保留既有行为）。 */
        VOID("order.void", "订单作废", "作废", "order-void", false, false);

        private final String action;
        private final String actionLabel;
        private final String verb;
        private final String idempotencyKeyPrefix;
        private final boolean reasonRequired;
        private final boolean idempotentWhenAlreadyCancelled;

        CancelKind(String action, String actionLabel, String verb, String idempotencyKeyPrefix,
                   boolean reasonRequired, boolean idempotentWhenAlreadyCancelled) {
            this.action = action;
            this.actionLabel = actionLabel;
            this.verb = verb;
            this.idempotencyKeyPrefix = idempotencyKeyPrefix;
            this.reasonRequired = reasonRequired;
            this.idempotentWhenAlreadyCancelled = idempotentWhenAlreadyCancelled;
        }

        public String action() {
            return action;
        }

        public String actionLabel() {
            return actionLabel;
        }

        public String verb() {
            return verb;
        }

        /** 稳定幂等键：同一订单重复提交只留一条操作日志（审计服务按键去重）。 */
        public String idempotencyKey(Long orderId) {
            return idempotencyKeyPrefix + ":" + orderId;
        }

        public boolean reasonRequired() {
            return reasonRequired;
        }

        public boolean idempotentWhenAlreadyCancelled() {
            return idempotentWhenAlreadyCancelled;
        }
    }

    private final OrderMapper orderMapper;
    private final KtvSessionApplicationService ktvSessionService;
    private final AuditClient auditClient;
    private final PaymentCollectedClient paymentCollectedClient;

    @Autowired
    public OrderCancellationApplicationService(OrderMapper orderMapper,
                                               KtvSessionApplicationService ktvSessionService,
                                               AuditClient auditClient,
                                               PaymentCollectedClient paymentCollectedClient) {
        this.orderMapper = orderMapper;
        this.ktvSessionService = ktvSessionService;
        this.auditClient = auditClient;
        this.paymentCollectedClient = paymentCollectedClient;
    }

    /** 兼容单测/旧装配：没有 payment 域客户端时只用订单已收金额快照判定。 */
    public OrderCancellationApplicationService(OrderMapper orderMapper,
                                               KtvSessionApplicationService ktvSessionService,
                                               AuditClient auditClient) {
        this(orderMapper, ktvSessionService, auditClient, null);
    }

    /** 运营取消订单（原因必填；重复取消幂等返回既有结果，不再留痕）。 */
    @Transactional
    public OrderPo cancel(Long orderId, String reason) {
        return apply(orderId, normalizeReason(reason, CancelKind.CANCEL), CancelKind.CANCEL);
    }

    /** 作废订单（既有入口，原因选填；保留既有审计码与已作废 409 行为）。 */
    @Transactional
    public OrderPo voidOrder(Long orderId, String reason) {
        return apply(orderId, normalizeReason(reason, CancelKind.VOID), CancelKind.VOID);
    }

    /** 原因口径由动作类型决定：取消必填（400 CANCEL_REASON_REQUIRED），作废保留选填；长度上限一致。 */
    private static String normalizeReason(String reason, CancelKind kind) {
        return kind.reasonRequired()
                ? CancelReasons.require(reason, kind.verb() + "订单必须填写原因")
                : CancelReasons.optional(reason);
    }

    /** 订单查询（404 不存在 / 403 跨租户），与控制器 requireOrder 同一口径，集中在本服务避免两处漂移。 */
    public OrderPo requireOrder(Long id) {
        OrderPo po = orderMapper.selectById(id);
        if (po == null) {
            if (orderMapper.selectTenantIdById(id) != null) {
                throw new ApiException(403, "TENANT_SCOPE_DENIED", "无权访问其他租户的订单");
            }
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }
        return po;
    }

    private OrderPo apply(Long orderId, String reason, CancelKind kind) {
        OrderPo order = requireOrder(orderId);
        String beforeStatus = order.getStatus();
        try {
            if ("VOIDED".equals(beforeStatus)) {
                if (kind.idempotentWhenAlreadyCancelled()) {
                    // 幂等：重复取消返回既有结果，不写库、不重复释放占用、不重复留痕。
                    return order;
                }
                throw new ApiException(409, "ORDER_STATUS_INVALID", "订单已作废，不能重复作废");
            }
            if (TERMINAL_STATUSES.contains(beforeStatus)) {
                // 状态冲突必须是 409（错误码字典里 ORDER_STATUS_INVALID 由全局处理器映射为 422，
                // 取消/作废是「与当前订单状态冲突」，这里显式给 409，避免调用方把它当参数错误）。
                throw new ApiException(409, "ORDER_STATUS_INVALID",
                        "仅未完成订单可" + kind.verb() + "，当前状态：" + beforeStatus);
            }
            requireNoCollectedPayment(order);

            // 释放包厢占用：同一事务内取消活动会话并释放占用（会话取消/释放语义统一在 KTV 会话服务里）。
            KtvSessionPo releasedSession = ktvSessionService.cancelByOrder(order.getId());

            LocalDateTime now = LocalDateTime.now();
            order.setStatus("VOIDED");
            order.setCancelledAt(now);
            order.setUpdatedAt(now);
            orderMapper.updateById(order);
            audit(order, beforeStatus, reason, kind, releasedSession);
            return order;
        } catch (RuntimeException failure) {
            // 失败留痕（状态冲突/已收款拦截/释放占用或落库失败）：BFF 拦截器只覆盖 /admin/**，
            // /business/** 取消订单的失败此前完全没有留痕。审计只 WARN、异常原样抛出。
            recordFailure(order, beforeStatus, kind, failure);
            throw failure;
        }
    }

    /**
     * 取消/作废失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode。
     * <p>不带幂等键（同一动作重复失败必须各自留痕，且不得与成功路径的稳定键互相覆盖）；
     * detail 只放订单号与状态等检索字段，不含金额、联系人等敏感信息。
     */
    private void recordFailure(OrderPo order, String beforeStatus, CancelKind kind, RuntimeException failure) {
        if (auditClient == null) {
            return;
        }
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(order.getTenantId())
                .storeId(order.getStoreId())
                .action(kind.action())
                .actionLabel(kind.actionLabel())
                .resourceType("ord_order")
                .resourceId(String.valueOf(order.getId()))
                .resourceName(order.getOrderNo())
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"orderNo\":" + jsonText(order.getOrderNo())
                        + ",\"beforeStatus\":" + jsonText(beforeStatus) + "}")
                .build());
    }

    /**
     * 已收款拦截：订单金额快照 {@code paid_amount > 0} 直接拒绝；
     * 快照为 0 时再向 payment 域确认一次「是否存在成功收款流水」（{@code pay_collect.state=CONFIRMED}），
     * 因为 {@code ord_order.paid_amount} 的回写依赖 payment 域，单看快照可能漏判。
     *
     * <p>payment 域不可达时按「无收款证据」处理（不阻断取消），与账单读路径同一降级口径：
     * 资金拦截的最终依据仍是订单已收金额快照。
     */
    private void requireNoCollectedPayment(OrderPo order) {
        BigDecimal paid = order.getPaidAmount();
        if (paid != null && paid.compareTo(BigDecimal.ZERO) > 0) {
            throw new ApiException(409, PAYMENT_REFUND_FIRST_CODE, PAYMENT_REFUND_FIRST_MESSAGE);
        }
        if (paymentCollectedClient == null) {
            return;
        }
        Optional<PaymentCollectedClient.CollectedBreakdown> collected = paymentCollectedClient.collected(order.getId());
        if (collected.isPresent() && totalCollected(collected.get()) > 0) {
            throw new ApiException(409, PAYMENT_REFUND_FIRST_CODE, PAYMENT_REFUND_FIRST_MESSAGE);
        }
    }

    /** 已收合计（现金 + 储值 + 积分，最小货币单位）：payment 域只回「成功收款」分腿。 */
    private static long totalCollected(PaymentCollectedClient.CollectedBreakdown breakdown) {
        return breakdown.cash() + breakdown.wallet() + breakdown.points();
    }

    /** 取消/作废审计：动作码按 kind 分叉，detail 带前后状态、原因与释放的会话/包厢（无会话时为 null）。 */
    private void audit(OrderPo order, String beforeStatus, String reason, CancelKind kind,
                       KtvSessionPo releasedSession) {
        if (auditClient == null) {
            return;
        }
        Long releasedSessionId = releasedSession == null ? null : releasedSession.getId();
        Long releasedResourceId = releasedSession == null ? null : releasedSession.getRoomResourceId();
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(order.getTenantId())
                .storeId(order.getStoreId())
                .action(kind.action())
                .actionLabel(kind.actionLabel())
                .resourceType("ord_order")
                .resourceId(String.valueOf(order.getId()))
                .resourceName(order.getOrderNo())
                .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                .idempotencyKey(kind.idempotencyKey(order.getId()))
                .detailJson(detail(order, beforeStatus, reason, releasedSessionId, releasedResourceId))
                .build());
    }

    private static String detail(OrderPo order, String beforeStatus, String reason,
                                 Long releasedSessionId, Long releasedResourceId) {
        return "{\"orderNo\":" + jsonText(order.getOrderNo())
                + ",\"beforeStatus\":" + jsonText(beforeStatus)
                + ",\"afterStatus\":" + jsonText(order.getStatus())
                + ",\"reason\":" + jsonText(reason)
                + ",\"releasedSessionId\":" + releasedSessionId
                + ",\"releasedResourceId\":" + releasedResourceId + "}";
    }

    /** 最小 JSON 字符串转义（原因/订单号是运营自由文本，未转义会拼出非法 JSON 丢审计）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
