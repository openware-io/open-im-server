package io.openware.common.payment.application;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.domain.billing.model.OrderReceivable;
import io.openware.common.payment.domain.billing.repository.OrderBillingRepository;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.intent.repository.PaymentIntentRepository;
import io.openware.common.payment.domain.settlement.model.ChannelOrderSnapshot;
import io.openware.common.payment.domain.settlement.model.ChannelSettlement;
import io.openware.common.payment.domain.settlement.repository.ChannelSettlementRepository;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 渠道回调幂等结算桥（payment 域）：渠道验签后的资金事实 → 支付台账 + 订单收款/退款。
 *
 * <p>与 {@link CollectApplicationService} 的关系：组合收款是"收银台发起、本库原子落账"；本服务是
 * "渠道异步回调发起"，渠道侧可能重复/乱序投递，因此**幂等靠渠道交易号**而不是 {@code Idempotency-Key}。
 *
 * <p>一致性口径：
 * <ul>
 *   <li><b>收款成功</b>：在**同一个本地事务**内落 {@code pay_intent}(SUCCEEDED) +
 *       {@code pay_transaction}(SUCCEEDED)，并把订单已收金额从 {@code expectedPaid} 推到
 *       {@code expectedPaid + amount}（{@code markPaid} CAS），最后写结算幂等台账；任一步失败整体回滚。</li>
 *   <li><b>重复回调</b>：结算台账唯一键 {@code (provider, transaction_id, kind)} 命中即回放，
 *       返回首次成功后的订单状态，不再重复记账（也不会二次调用 markPaid）。</li>
 *   <li><b>失败/关闭</b>：不改订单收款，也不写资金流水，仅返回 accepted（幂等天然成立）。</li>
 *   <li><b>退款成功</b>：按退款金额回冲订单已收/可退与 {@code refund_status}；同样以结算台账幂等。</li>
 *   <li><b>scope 校验</b>：租户/门店以订单主表为准；请求携带的租户/门店与订单不一致一律 422，
 *       币种与订单快照不一致 422，收款金额必须等于订单剩余应收，否则 422（禁止跨 scope 与跨币种）。</li>
 * </ul>
 *
 * <p>金额口径沿用仓储既有约定：{@code pay_intent/pay_transaction/ord_order} 的金额列存
 * **最小货币单位整数**（如 CNY 分），本服务与 {@code CollectApplicationService} 保持同一口径。
 */
@Service
@Slf4j
public class ChannelSettlementApplicationService {

    /** 收款成功：落支付意图/交易并推进订单已收。 */
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** 退款成功：回冲订单已收/可退。 */
    private static final String STATUS_REFUNDED = "REFUNDED";
    /** 渠道明确失败：不落任何资金事实。 */
    private static final String STATUS_FAILED = "FAILED";
    /** 渠道关闭交易（支付宝 TRADE_CLOSED / 微信 CLOSED / Stripe canceled）：不落任何资金事实。 */
    private static final String STATUS_CLOSED = "CLOSED";
    /** 渠道处理中：不落任何资金事实。 */
    private static final String STATUS_PROCESSING = "PROCESSING";
    /** 需要改动账本的渠道状态。 */
    private static final Set<String> APPLYING_STATUSES = Set.of(STATUS_SUCCEEDED, STATUS_REFUNDED);
    /** 验签通过但无需改动账本、且允许对未知订单快速确认的状态（避免渠道无休止重试）。 */
    private static final Set<String> NON_APPLYING_STATUSES = Set.of(STATUS_FAILED, STATUS_CLOSED, STATUS_PROCESSING);

    private static final String SETTLEMENT_TYPE_NONE = "NONE";
    private static final String IDEMPOTENCY_PREFIX = "CH:";
    private static final int MAX_PROVIDER_LENGTH = 16;
    private static final int MAX_TRANSACTION_ID_LENGTH = 64;
    private static final int MAX_EVENT_ID_LENGTH = 64;
    private static final int IDEMPOTENCY_HASH_LENGTH = 40;

    private final ChannelSettlementRepository settlementRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final OrderBillingRepository orderBillingRepository;
    private final TransactionTemplate transactionTemplate;

    public ChannelSettlementApplicationService(ChannelSettlementRepository settlementRepository,
                                               PaymentIntentRepository paymentIntentRepository,
                                               PaymentTransactionRepository paymentTransactionRepository,
                                               OrderBillingRepository orderBillingRepository,
                                               PlatformTransactionManager transactionManager) {
        this.settlementRepository = settlementRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.orderBillingRepository = orderBillingRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 结算一笔渠道回调。返回值语义见 {@link ChannelSettlementResult}；
     * 只有收款/退款成功会写资金事实，失败/关闭/处理中一律不改订单。
     */
    public ChannelSettlementResult settle(ChannelSettlementCommand command) {
        String provider = requireProvider(command.provider());
        String transactionId = requireTransactionId(command.transactionId());
        String eventId = normalizeEventId(command.eventId());
        String channelStatus = requireStatus(command.status());
        long orderId = parseOrderId(command.orderId());

        // scope 解析（只读）：渠道回调只带 orderId，租户/门店以订单主表为准。
        Optional<ChannelOrderSnapshot> scope = settlementRepository.findOrderSnapshot(orderId);
        if (scope.isEmpty()) {
            if (NON_APPLYING_STATUSES.contains(channelStatus)) {
                // 失败/关闭回调对未知订单无需落账：确认掉，避免渠道无休止重试（不产生任何资金事实）。
                log.warn("渠道回调对应订单不存在，且状态无需落账，按确认处理: provider={} transactionId={} orderId={} status={}",
                        provider, transactionId, orderId, channelStatus);
                return notApplied(provider, transactionId, eventId, orderId, channelStatus, null);
            }
            throw new ApiException(404, "ORDER_NOT_FOUND", "订单不存在: " + orderId);
        }
        ChannelOrderSnapshot order = scope.get();
        long tenantId = resolveTenantId(order, command.tenantId());
        verifyStoreScope(order, command.storeId());

        // 内部结算请求没有网关下发的租户上下文：租户以**订单主表**为准（不是回调或头里的自述值），
        // 解析出来后切到该上下文，后续 pay_intent/pay_transaction/结算台账的租户行拦截器才能正确工作。
        // 只在上下文缺失或租户不同时切换，结束后恢复原上下文，避免污染调用线程。
        TenantContext previous = TenantContextHolder.get();
        boolean contextSwitched = previous == null || previous.tenantId() != tenantId;
        try {
            if (contextSwitched) {
                TenantContextHolder.set(new TenantContext(tenantId, null, order.storeId(), 0L, 0));
            }
            if (!APPLYING_STATUSES.contains(channelStatus)) {
                if (!NON_APPLYING_STATUSES.contains(channelStatus)) {
                    throw new ApiException(422, "INVALID_CALLBACK_STATUS",
                            "不支持的渠道回调状态: " + command.status());
                }
                // 失败/关闭/处理中：不改订单收款，不写 pay_intent/pay_transaction。
                log.info("渠道回调状态无需落账: provider={} transactionId={} orderId={} status={}",
                        provider, transactionId, orderId, channelStatus);
                return notApplied(provider, transactionId, eventId, orderId, channelStatus, order);
            }

            ChannelSettlementResult result = STATUS_REFUNDED.equals(channelStatus)
                    ? settleRefund(provider, transactionId, eventId, tenantId, orderId, command)
                    : settlePayment(provider, transactionId, eventId, tenantId, orderId, command);
            log.info("渠道回调结算完成: provider={} transactionId={} orderId={} status={} applied={} replayed={}",
                    provider, transactionId, orderId, channelStatus, result.applied(), result.replayed());
            return result;
        } finally {
            if (contextSwitched) {
                if (previous == null) {
                    TenantContextHolder.clear();
                } else {
                    TenantContextHolder.set(previous);
                }
            }
        }
    }

    /** 收款成功：原子落支付意图 + 交易 + 订单收款 + 幂等台账；重复回调回放。 */
    private ChannelSettlementResult settlePayment(String provider, String transactionId, String eventId,
                                                  long tenantId, long orderId, ChannelSettlementCommand command) {
        long amountMinor = requirePositiveAmount(command.amountMinor(), "渠道回调金额非法");
        return inTransaction(() -> {
            // 锁订单主行：并发回调/并发组合收款在同一把锁上串行，后续 CAS 与幂等判定才有意义。
            ChannelOrderSnapshot locked = settlementRepository.findOrderSnapshot(tenantId, orderId)
                    .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "订单不存在: " + orderId));
            Optional<ChannelSettlement> existing =
                    settlementRepository.findByTransaction(provider, transactionId, ChannelSettlement.KIND_PAYMENT);
            if (existing.isPresent()) {
                return replay(existing.get(), locked, provider, transactionId, eventId, ChannelSettlement.KIND_PAYMENT);
            }
            String currency = resolveCurrency(locked.currencyCode(), command.currency());
            if (!collectable(locked.tradeStatus())) {
                throw new ApiException(422, "ORDER_STATUS_INVALID",
                        "订单当前不可收款: " + locked.tradeStatus());
            }
            long remaining = locked.remainingPayable();
            if (amountMinor != remaining) {
                throw new ApiException(422, "PAYMENT_AMOUNT_MISMATCH",
                        "渠道回调金额与订单剩余应收不一致: callback=" + amountMinor + ", remaining=" + remaining);
            }
            LocalDateTime now = LocalDateTime.now();
            BigDecimal amount = BigDecimal.valueOf(amountMinor);
            PaymentIntent intent = paymentIntentRepository.insert(new PaymentIntent(null, tenantId, locked.storeId(),
                    orderId, null, provider, provider, amount, currency, PaymentIntent.STATUS_SUCCEEDED,
                    idempotencyKey(provider, transactionId), null, now, now));
            PaymentTransaction transaction = paymentTransactionRepository.insert(new PaymentTransaction(null, tenantId,
                    locked.storeId(), null, intent.id(), provider, transactionId, null, amount, currency,
                    null, null, PaymentTransaction.STATUS_SUCCEEDED, now, null));
            BigDecimal expectedPaid = locked.paidOrZero();
            boolean marked = orderBillingRepository.markPaid(tenantId, orderId, expectedPaid,
                    expectedPaid.add(amount), locked.totalOrZero());
            if (!marked) {
                throw new ApiException(409, "PAYMENT_ORDER_CONFLICT", "订单已被其他收款操作更新，请重试");
            }
            ChannelSettlement settlement = insertSettlement(new ChannelSettlement(null, tenantId, locked.storeId(),
                    orderId, provider, transactionId, eventId, ChannelSettlement.KIND_PAYMENT, amount, currency,
                    ChannelSettlement.STATUS_SUCCEEDED, intent.id(), transaction.id()));
            ChannelOrderSnapshot after = settlementRepository.findOrderSnapshot(tenantId, orderId).orElse(locked);
            return new ChannelSettlementResult(provider, transactionId, eventId, orderId, STATUS_SUCCEEDED,
                    ChannelSettlement.KIND_PAYMENT, true, false, intent.id(), transaction.id(),
                    after.tradeStatus(), after.refundStatus(), after.paidOrZero(), currency, amount);
        });
    }

    /** 退款成功：回冲订单已收/可退 + 幂等台账；重复回调回放，不重复回冲。 */
    private ChannelSettlementResult settleRefund(String provider, String transactionId, String eventId,
                                                 long tenantId, long orderId, ChannelSettlementCommand command) {
        return inTransaction(() -> {
            ChannelOrderSnapshot locked = settlementRepository.findOrderSnapshot(tenantId, orderId)
                    .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "订单不存在: " + orderId));
            Optional<ChannelSettlement> existing =
                    settlementRepository.findByTransaction(provider, transactionId, ChannelSettlement.KIND_REFUND);
            if (existing.isPresent()) {
                return replay(existing.get(), locked, provider, transactionId, eventId, ChannelSettlement.KIND_REFUND);
            }
            String currency = resolveCurrency(locked.currencyCode(), command.currency());
            if (!refundable(locked.tradeStatus())) {
                throw new ApiException(422, "ORDER_STATUS_INVALID",
                        "订单当前不可退款: " + locked.tradeStatus());
            }
            long refundAmount = resolveRefundAmount(command);
            BigDecimal refund = BigDecimal.valueOf(refundAmount);
            if (refund.compareTo(locked.paidOrZero()) > 0) {
                throw new ApiException(422, "REFUND_AMOUNT_EXCEEDS_PAID",
                        "退款金额不得超过订单已收金额: refund=" + refundAmount
                                + ", paid=" + locked.paidOrZero().toPlainString());
            }
            boolean marked = orderBillingRepository.markRefunded(tenantId, orderId, refund);
            if (!marked) {
                throw new ApiException(422, "ORDER_REFUND_REJECTED", "订单当前状态不接受退款落账");
            }
            insertSettlement(new ChannelSettlement(null, tenantId, locked.storeId(), orderId, provider,
                    transactionId, eventId, ChannelSettlement.KIND_REFUND, refund, currency,
                    ChannelSettlement.STATUS_REFUNDED, null, null));
            ChannelOrderSnapshot after = settlementRepository.findOrderSnapshot(tenantId, orderId).orElse(locked);
            return new ChannelSettlementResult(provider, transactionId, eventId, orderId, STATUS_REFUNDED,
                    ChannelSettlement.KIND_REFUND, true, false, null, null,
                    after.tradeStatus(), after.refundStatus(), after.paidOrZero(), currency, refund);
        });
    }

    /** 幂等回放：返回首次成功后的订单当前状态，绝不再写账本。 */
    private ChannelSettlementResult replay(ChannelSettlement existing, ChannelOrderSnapshot order,
                                           String provider, String transactionId, String eventId, String kind) {
        if (!Objects.equals(existing.orderId(), order.orderId())) {
            throw new ApiException(422, "SETTLEMENT_SCOPE_MISMATCH", "同一渠道交易命中了不同订单的结算记录");
        }
        return new ChannelSettlementResult(provider, transactionId, eventId, order.orderId(),
                STATUS_SUCCEEDED.equals(existing.status()) ? STATUS_SUCCEEDED : STATUS_REFUNDED,
                kind, false, true, existing.payIntentId(), existing.payTransactionId(),
                order.tradeStatus(), order.refundStatus(), order.paidOrZero(),
                existing.currencyCode(), existing.amount());
    }

    private ChannelSettlement insertSettlement(ChannelSettlement settlement) {
        try {
            return settlementRepository.insert(settlement);
        } catch (DuplicateKeyException concurrent) {
            // 并发重复回调：唯一键 (provider, transaction_id, kind) 兜底。回滚本次事务，让调用方重试后命中回放。
            log.warn("渠道结算并发重复，事务回滚后由重试命中幂等回放: provider={} transactionId={} kind={}",
                    settlement.provider(), settlement.transactionId(), settlement.kind());
            throw new ApiException(409, "SETTLEMENT_CONCURRENT", "渠道结算并发冲突，请重试");
        }
    }

    private ChannelSettlementResult notApplied(String provider, String transactionId, String eventId, long orderId,
                                               String channelStatus, ChannelOrderSnapshot order) {
        return new ChannelSettlementResult(provider, transactionId, eventId, orderId, channelStatus,
                SETTLEMENT_TYPE_NONE, false, false, null, null,
                order == null ? null : order.tradeStatus(), order == null ? null : order.refundStatus(),
                order == null ? null : order.paidOrZero(), order == null ? null : order.currencyCode(), null);
    }

    private ChannelSettlementResult inTransaction(Supplier<ChannelSettlementResult> action) {
        ChannelSettlementResult result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new IllegalStateException("渠道结算事务未返回结果");
        }
        return result;
    }

    /** 订单租户解析：请求未带租户时按订单补齐；请求带的租户与订单不一致一律拒绝。 */
    private static long resolveTenantId(ChannelOrderSnapshot order, Long requestedTenantId) {
        long orderTenantId = order.tenantId() == null ? 0L : order.tenantId();
        if (orderTenantId <= 0) {
            throw new ApiException(422, "ORDER_SCOPE_UNKNOWN", "订单缺少租户口径，无法结算");
        }
        if (requestedTenantId != null && requestedTenantId > 0 && requestedTenantId != orderTenantId) {
            throw new ApiException(422, "CROSS_TENANT_SCOPE", "渠道回调租户与订单租户不一致");
        }
        return orderTenantId;
    }

    /** 门店 range 校验：渠道回调不携带门店；一旦提供就必须与订单门店一致。 */
    private static void verifyStoreScope(ChannelOrderSnapshot order, Long requestedStoreId) {
        if (requestedStoreId == null || requestedStoreId <= 0) {
            return;
        }
        if (order.storeId() == null || !order.storeId().equals(requestedStoreId)) {
            throw new ApiException(422, "CROSS_STORE_SCOPE", "渠道回调门店与订单门店不一致");
        }
    }

    /**
     * 币种归一与一致性校验（16_CURRENCY_CONVENTIONS §5/§6）：请求币种必须与订单币种快照一致；
     * 请求未带币种时按订单补齐。订单币种缺失时取请求币种，仍为空回退缺省币种，绝不写入空币种。
     */
    private static String resolveCurrency(String orderCurrency, String requested) {
        if (requested != null && !requested.isBlank() && !Currency.isSupported(requested)) {
            throw new ApiException(400, "CURRENCY_UNSUPPORTED", "不支持的币种: " + requested);
        }
        String normalizedRequest = requested == null || requested.isBlank()
                ? null : Currency.parse(requested).code();
        if (orderCurrency != null && !orderCurrency.isBlank()) {
            String normalizedOrder = Currency.parse(orderCurrency).code();
            if (normalizedRequest != null && !normalizedOrder.equals(normalizedRequest)) {
                throw new ApiException(422, "CURRENCY_MISMATCH", "渠道回调币种必须与订单币种一致");
            }
            return normalizedOrder;
        }
        return normalizedRequest == null ? Currency.DEFAULT.code() : normalizedRequest;
    }

    private static long resolveRefundAmount(ChannelSettlementCommand command) {
        Long refundAmount = command.refundAmount();
        if (refundAmount != null && refundAmount > 0) {
            return refundAmount;
        }
        long amountMinor = requirePositiveAmount(command.amountMinor(), "退款回调金额非法");
        return amountMinor;
    }

    private static long requirePositiveAmount(Long amountMinor, String message) {
        if (amountMinor == null || amountMinor <= 0) {
            throw new ApiException(422, "PAYMENT_AMOUNT_MISMATCH", message);
        }
        return amountMinor;
    }

    private static boolean collectable(String tradeStatus) {
        return !OrderReceivable.TRADE_CANCELLED.equals(tradeStatus)
                && !OrderReceivable.TRADE_VOIDED.equals(tradeStatus)
                && !OrderReceivable.TRADE_COMPLETED.equals(tradeStatus);
    }

    private static boolean refundable(String tradeStatus) {
        return !OrderReceivable.TRADE_CANCELLED.equals(tradeStatus)
                && !OrderReceivable.TRADE_VOIDED.equals(tradeStatus);
    }

    private static String requireProvider(String provider) {
        String normalized = provider == null || provider.isBlank() ? null
                : provider.trim().toUpperCase(Locale.ROOT);
        if (normalized == null) {
            throw new ApiException(400, "PROVIDER_REQUIRED", "缺少渠道标识");
        }
        if (normalized.length() > MAX_PROVIDER_LENGTH) {
            throw new ApiException(400, "PROVIDER_INVALID", "渠道标识超长: " + normalized);
        }
        return normalized;
    }

    private static String requireTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new ApiException(400, "TRANSACTION_ID_REQUIRED", "缺少渠道交易号");
        }
        String normalized = transactionId.trim();
        if (normalized.length() > MAX_TRANSACTION_ID_LENGTH) {
            throw new ApiException(400, "TRANSACTION_ID_INVALID", "渠道交易号超长");
        }
        return normalized;
    }

    private static String normalizeEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        String normalized = eventId.trim();
        if (normalized.length() > MAX_EVENT_ID_LENGTH) {
            throw new ApiException(400, "EVENT_ID_INVALID", "渠道回调事件 ID 超长");
        }
        return normalized;
    }

    private static String requireStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ApiException(400, "STATUS_REQUIRED", "缺少渠道回调状态");
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static long parseOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new ApiException(400, "ORDER_ID_REQUIRED", "缺少订单号");
        }
        try {
            long parsed = Long.parseLong(orderId.trim());
            if (parsed <= 0) {
                throw new NumberFormatException("orderId must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            // 渠道 out_trade_no 本应是数字订单号；非数字说明回调口径不对，必须 400 而不是静默跳过。
            throw new ApiException(400, "ORDER_ID_INVALID", "订单号非法: " + orderId);
        }
    }

    /**
     * 支付意图幂等键：{@code CH:<provider>:<sha256(transactionId)[:40]>}（长度 ≤ 64）。
     *
     * <p>渠道交易号最长 64 字符，直接拼进 {@code idempotency_key varchar(64)} 会超长；哈希截断既保留
     * provider 维度可读性，又让键长固定。真正的幂等判据仍是结算台账唯一键，本键是二次防护。
     */
    private static String idempotencyKey(String provider, String transactionId) {
        String key = IDEMPOTENCY_PREFIX + provider + ":" + hash(transactionId);
        if (key.length() > 64) {
            throw new IllegalStateException("渠道结算幂等键超长: " + key.length());
        }
        return key;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, IDEMPOTENCY_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash the channel transaction id", e);
        }
    }
}
