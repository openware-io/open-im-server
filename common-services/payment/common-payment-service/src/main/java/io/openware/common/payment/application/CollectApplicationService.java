package io.openware.common.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.client.CustomerClient;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.common.payment.infra.persistence.mapper.PayCollectMapper;
import io.openware.common.payment.infra.persistence.mapper.PayIntentMapper;
import io.openware.common.payment.infra.persistence.mapper.PayTransactionMapper;
import io.openware.common.payment.infra.persistence.mapper.OrderBillingMapper;
import io.openware.common.payment.infra.persistence.po.OrderBillingPo;
import io.openware.common.payment.infra.persistence.po.PayCollectPo;
import io.openware.common.payment.infra.persistence.po.PayIntentPo;
import io.openware.common.payment.infra.persistence.po.PayTransactionPo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 组合收款（对齐 KTV_BUSINESS_01 §7.2 / SAAS_PLATFORM_06 §7.2 / SAAS_PLATFORM_05 §7）：
 * 抵扣顺序「优惠→积分→储值→现金」；优惠在结算阶段已应用，本服务收应付余额并按 积分→储值→现金 逐笔拆分。
 * 金额最小货币单位整数（long）；每笔 ≤ 剩余应收；舍入让利消费者（抹零向下，由结算阶段固化）。
 *
 * 一致性（先校验 + 本库原子 + 跨服务补偿）：
 * - 前置校验：任何分腿落库之前先校验「各分腿合计 == 服务端账单应收」「单腿不超剩余」「金额非负」
 *   「方式合法且已授权」，不满足直接 422，且不产生任何 pay_collect/pay_intent/pay_transaction 行。
 * - 本库原子：现金/线上分腿的 pay_intent + pay_transaction、ord_order 落账（paid_amount/状态）与
 *   pay_collect 状态机（CONFIRMED）在同一个事务内提交或一起回滚；不会再出现「现金分腿已提交而
 *   pay_collect=FAILED」这种资金三方对不上的残留（此前现金分腿跑在独立事务里且先于足额校验提交）。
 * - 跨服务补偿：积分/储值扣减在 customer 域，无法纳入本地事务；失败时按反向顺序归还（释放端点按幂等键
 *   幂等），补偿结果随失败快照落库，供同键重试续做，避免重复扣减。
 * - 幂等：同一 Idempotency-Key 命中 CONFIRMED 直接回放首次结果；命中 FAILED（且补偿快照完整）则接管该
 *   记录重跑全流程；INIT/HOLD 或缺少补偿快照的旧记录返回 409，不允许并发或状态不明时重跑。
 * - 分腿幂等键由「收款单号 + 本次尝试随机段 + 分腿」派生：重跑不会撞上 customer 侧已消费的幂等键，
 *   也不会重复扣减；首次尝试的补偿键可从失败快照还原。
 */
@Service
@Slf4j
public class CollectApplicationService {
    private static final int METHOD_POINT = 0;
    private static final int METHOD_WALLET = 1;
    private static final int METHOD_CASH = 2;

    private static final String STATE_INIT = "INIT";
    private static final String STATE_CONFIRMED = "CONFIRMED";
    private static final String STATE_FAILED = "FAILED";

    private static final String LEG_POINT = "POINT";
    private static final String LEG_WALLET = "WALLET";

    private final PayIntentMapper payIntentMapper;
    private final PayTransactionMapper payTransactionMapper;
    private final PayCollectMapper payCollectMapper;
    private final CustomerClient customerClient;
    private final PaymentMethodApplicationService paymentMethodService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionTemplate transactionTemplate;
    private final AuditClient auditClient;
    private final OrderBillingMapper orderBillingMapper;

    public CollectApplicationService(PayIntentMapper payIntentMapper,
                                     PayTransactionMapper payTransactionMapper,
                                     PayCollectMapper payCollectMapper,
                                     CustomerClient customerClient,
                                     PaymentMethodApplicationService paymentMethodService,
                                     PlatformTransactionManager transactionManager,
                                     AuditClient auditClient,
                                     OrderBillingMapper orderBillingMapper) {
        this.payIntentMapper = payIntentMapper;
        this.payTransactionMapper = payTransactionMapper;
        this.payCollectMapper = payCollectMapper;
        this.customerClient = customerClient;
        this.paymentMethodService = paymentMethodService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.auditClient = auditClient;
        this.orderBillingMapper = orderBillingMapper;
    }

    /**
     * @param customerId 会员档案ID（积分/储值扣减用；纯现金收款可为 null）
     * @param payable 剩余应收（最小货币单位整数，必须等于服务端账单应收）
     * @param idempotencyKey 组合收款幂等键（Idempotency-Key）
     */
    public CollectResult collect(Long tenantId, Long storeId, Long orderId, Long customerId, String currencyCode,
                                 long payable, List<PaymentItem> payments, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        if (payable < 0) {
            throw new ApiException(400, "AMOUNT_INVALID", "应收金额非法");
        }

        // 幂等优先：已确认的同一 Idempotency-Key 直接回放首次结果，不再重复校验/落库/调下游。
        PayCollectPo existing = findByIdempotencyKey(tenantId, idempotencyKey);
        if (existing != null && STATE_CONFIRMED.equals(existing.getState()) && existing.getResponseJson() != null) {
            return deserializeResult(existing.getResponseJson());
        }
        if (existing != null && !STATE_FAILED.equals(existing.getState())) {
            // INIT/HOLD：首次请求仍在处理中，或进程在落库前中断，无法确认 customer 侧扣减状态。
            throw new ApiException(409, "PAYMENT_PROCESSING", "组合收款处理中，请勿重复提交");
        }

        // 服务端应收校验（只读一次并复用，避免校验阶段重复查库）：应收以服务端账单为准。
        OrderBillingPo billing = orderBillingMapper == null ? null : orderBillingMapper.selectForUpdate(tenantId, orderId);
        BigDecimal billPaid = null;
        BigDecimal billTotal = null;
        if (billing != null) {
            billTotal = billing.getTotalAmount() == null ? BigDecimal.ZERO : billing.getTotalAmount();
            billPaid = billing.getPaidAmount() == null ? BigDecimal.ZERO : billing.getPaidAmount();
            long serverPayable = billTotal.subtract(billPaid).max(BigDecimal.ZERO).longValueExact();
            if (payable != serverPayable) {
                throw new ApiException(422, "PAYMENT_AMOUNT_MISMATCH", "应收金额必须以服务端账单为准");
            }
            if ("CANCELLED".equals(billing.getStatus()) || "VOIDED".equals(billing.getStatus())
                    || "COMPLETED".equals(billing.getStatus())) {
                throw new ApiException(422, "ORDER_STATUS_INVALID", "订单当前不可收款");
            }
        }

        // 币种归一 + 一致性校验（16_CURRENCY_CONVENTIONS §5「空值按当时租户币种补齐」/ §6「禁止跨币种交易」）。
        // 放在任何落库之前：不满足直接 422，不产生 pay_collect/pay_intent/pay_transaction 行。
        // 用新的 final 局部量承接（不能重赋值入参：下游 lambda 需要 effectively-final）。
        final String effectiveCurrency = resolveCollectCurrency(billing, currencyCode);

        // 前置校验：所有分腿在落库之前一次性校验（金额、方式、合计），不满足直接 422 且不落任何行。
        List<PaymentItem> ordered = validateLegs(tenantId, storeId, payable, effectiveCurrency, payments);

        PayCollectPo collect;
        if (existing != null) {
            // 重跑前先续做上一次未完成的补偿（释放端点按幂等键幂等，重复调用不会重复归还）；
            // 补偿未落地或旧记录缺少补偿快照时拒绝重跑，避免重复扣减。
            settlePendingReleases(existing, tenantId, storeId, orderId, customerId, effectiveCurrency);
            if (!claimFailedRecord(existing)) {
                throw new ApiException(409, "PAYMENT_PROCESSING", "组合收款处理中，请勿重复提交");
            }
            collect = existing;
        } else {
            collect = newCollectRecord(tenantId, orderId, customerId, effectiveCurrency, payable, payments, idempotencyKey);
            try {
                payCollectMapper.insert(collect);
            } catch (DuplicateKeyException dup) {
                // 唯一键 uk_pay_collect_idem 兜底并发：另一请求已建立同一幂等键的记录。
                PayCollectPo winner = findByIdempotencyKey(tenantId, idempotencyKey);
                if (winner != null && STATE_CONFIRMED.equals(winner.getState()) && winner.getResponseJson() != null) {
                    return deserializeResult(winner.getResponseJson());
                }
                throw new ApiException(409, "PAYMENT_PROCESSING", "组合收款处理中，请勿重复提交");
            }
        }

        // 本次尝试独立的分腿幂等键前缀（收款单号 + 随机段）：重跑不复用，避免撞上 customer 侧已消费的键。
        String attemptKey = newAttemptKey(collect.getCollectNo());
        List<HeldLeg> heldLegs = new ArrayList<>();
        List<PaymentItem> cashLegs = new ArrayList<>();
        List<CollectedMethod> collected = new ArrayList<>();
        try {
            // HOLD：积分/储值先扣减（跨服务，失败需补偿）；现金/线上分腿延后到本地事务内落库。
            for (PaymentItem p : ordered) {
                int kind = methodOf(p.method());
                if (kind == METHOD_POINT) {
                    redeemPoints(tenantId, storeId, orderId, customerId, p.amount(), attemptKey);
                    heldLegs.add(new HeldLeg(LEG_POINT, p.amount()));
                } else if (kind == METHOD_WALLET) {
                    deductWallet(tenantId, storeId, orderId, customerId, effectiveCurrency, p.amount(), attemptKey);
                    heldLegs.add(new HeldLeg(LEG_WALLET, p.amount()));
                } else {
                    cashLegs.add(p);
                }
                collected.add(new CollectedMethod(p.method(), p.amount()));
            }

            CollectResult result = new CollectResult(0L, collected);
            List<PaymentItem> cashLegsFinal = List.copyOf(cashLegs);
            BigDecimal expectedPaid = billPaid;
            BigDecimal total = billTotal;
            // CONSUME：现金/线上分腿 + 订单落账 + pay_collect 状态机在同一事务内提交，失败一起回滚。
            transactionTemplate.executeWithoutResult(status -> {
                for (int legNo = 0; legNo < cashLegsFinal.size(); legNo++) {
                    PaymentItem p = cashLegsFinal.get(legNo);
                    recordCash(tenantId, storeId, orderId, effectiveCurrency, p.amount(), attemptKey, p.method(), legNo + 1);
                }
                if (billing != null) {
                    BigDecimal newPaid = expectedPaid.add(BigDecimal.valueOf(payable));
                    if (orderBillingMapper.markPaid(tenantId, orderId, expectedPaid, newPaid, total) != 1) {
                        throw new ApiException(409, "PAYMENT_ORDER_CONFLICT", "订单已被其他收款操作更新，请重试");
                    }
                }
                markConfirmed(collect, result);
            });

            // 高风险写操作（组合收款）审计：异步占位，失败仅告警不阻断收款。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .action("payment.collect")
                    .actionLabel("组合收款")
                    .resourceType("collect")
                    .resourceId(collect.getCollectNo())
                    .idempotencyKey(idempotencyKey)
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"orderId\":" + orderId + ",\"payable\":" + payable + ",\"collected\":" + payable + "}")
                    .build());
            return result;
        } catch (RuntimeException ex) {
            // 失败 RELEASE：补偿退回已扣减的积分/储值；失败快照落库（含补偿结果），供同键重试续做。
            // 本地事务已回滚，因此现金分腿无需冲正：本库不残留任何已提交的资金流水。
            try {
                boolean compensated = releaseHeld(tenantId, storeId, orderId, customerId, effectiveCurrency, attemptKey, heldLegs);
                markFailed(collect, ex, attemptKey, heldLegs, compensated);
            } catch (RuntimeException recordEx) {
                // 失败处理不得掩盖原始异常：记录告警后继续抛出业务失败。
                log.error("组合收款失败状态记录失败，需人工核对: collectNo={} orderId={}",
                        collect.getCollectNo(), orderId, recordEx);
            }
            // 领域失败留痕：/business/** 组合收款没有 BFF 兜底，资金收款失败必须能按稳定码检索。
            // 放在补偿与失败快照之后、抛出之前：留痕只 WARN，绝不改变业务结果，也绝不掩盖原始异常。
            recordCollectFailure(tenantId, storeId, orderId, collect, ex);
            throw ex;
        }
    }

    /**
     * 组合收款失败留痕：动作码沿用成功路径 {@code payment.collect}，{@code result=FAILURE} + 稳定 errorCode
     * （{@link ApiException} 业务码优先，退化到异常类名并按列宽截断）。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（同一收款动作重复失败必须各自留痕，也绝不覆盖成功路径的幂等键）；
     * detail 只放订单/收款单号标识，<b>不含</b>金额、支付方式明细与会员信息。
     */
    private void recordCollectFailure(Long tenantId, Long storeId, Long orderId, PayCollectPo collect,
                                     RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .action("payment.collect")
                .actionLabel("组合收款")
                .resourceType("collect")
                .resourceId(collect == null ? null : collect.getCollectNo())
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"orderId\":" + orderId + ",\"collectNo\":"
                        + jsonText(collect == null ? null : collect.getCollectNo()) + "}")
                .build());
    }

    /** 最小 JSON 字符串转义（收款单号未转义会拼出非法 JSON 丢审计）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Resolves the member bound to a consumer-owned order; null means the caller does not own it. */
    public Long consumerCustomerId(Long tenantId, Long orderId, Long accountId) {
        if (tenantId == null || orderId == null || accountId == null || accountId <= 0) {
            return null;
        }
        return orderBillingMapper.selectOwnedCustomerId(tenantId, orderId, accountId);
    }

    /**
     * 按订单汇总已收分项（内部只读，供 order 域账单展示「现金/A380币/积分」分腿）。
     * 数据源是已确认的组合收款记录 pay_collect.response_json：同一订单可能分多次收款，
     * 逐笔累加各分腿；WALLET/POINT 之外的（现金/线上渠道）统一归入 cash。
     *
     * <p><b>币种（16_CURRENCY_CONVENTIONS §5「跨币种聚合要么拒绝要么显式标注」）</b>：本响应带
     * {@code currencyCode}（各笔收款币种快照一致时的该币种）与 {@code mixedCurrency} 标记；
     * 混币种时 {@code currencyCode=null} 且 {@code mixedCurrency=true}，调用方必须按标记处理，
     * 不得把不同币种的分腿当成一个可比较的合计。新收款已按「收款币种 = 订单币种」强校验
     * （见 {@link #resolveCollectCurrency}），混币种只可能来自历史数据。
     */
    public OrderCollected orderCollected(Long tenantId, Long orderId) {
        if (tenantId == null || orderId == null) {
            return new OrderCollected(orderId, null, false, 0L, 0L, 0L, 0L, List.of());
        }
        List<PayCollectPo> rows = payCollectMapper.selectList(new LambdaQueryWrapper<PayCollectPo>()
                .eq(PayCollectPo::getTenantId, tenantId)
                .eq(PayCollectPo::getOrderId, orderId)
                .eq(PayCollectPo::getState, STATE_CONFIRMED));
        Map<String, Long> byMethod = new LinkedHashMap<>();
        Set<String> currencies = new LinkedHashSet<>();
        long total = 0L;
        for (PayCollectPo row : rows) {
            String json = row.getResponseJson();
            if (json == null || json.isBlank()) {
                continue;
            }
            CollectResult result;
            try {
                result = deserializeResult(json);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (result.collectedByMethod() == null) {
                continue;
            }
            currencies.add(Currency.parse(row.getCurrencyCode()).code());
            for (CollectedMethod leg : result.collectedByMethod()) {
                if (leg.amount() <= 0) {
                    continue;
                }
                byMethod.merge(leg.method(), leg.amount(), Long::sum);
                total += leg.amount();
            }
        }
        long wallet = byMethod.getOrDefault("WALLET", 0L);
        long points = byMethod.getOrDefault("POINT", 0L);
        long cash = Math.max(0L, total - wallet - points);
        List<OrderCollectedLeg> legs = byMethod.entrySet().stream()
                .map(entry -> new OrderCollectedLeg(entry.getKey(), entry.getValue()))
                .toList();
        String currencyCode = currencies.size() == 1 ? currencies.iterator().next() : null;
        return new OrderCollected(orderId, currencyCode, currencies.size() > 1, total, cash, wallet, points, legs);
    }

    /**
     * 前置校验（落库前一次性完成，不产生任何 pay_collect/pay_intent/pay_transaction 行）：
     * 金额非负、支付方式合法且已授权、单腿不超剩余、各分腿合计必须等于服务端应收。
     * 返回按抵扣顺序（积分→储值→现金）排好并剔除零金额后的分腿。
     */
    private List<PaymentItem> validateLegs(Long tenantId, Long storeId, long payable, String currencyCode,
                                           List<PaymentItem> payments) {
        List<PaymentItem> ordered = payments == null ? List.of()
                : payments.stream()
                        .filter(p -> p != null && p.amount() != 0)
                        .sorted(Comparator.comparingInt(CollectApplicationService::methodPriority))
                        .toList();
        long remaining = payable;
        for (PaymentItem p : ordered) {
            if (p.amount() < 0) {
                throw new ApiException(422, "PAYMENT_AMOUNT_MISMATCH", "收款金额非法（不可为负）");
            }
            if (methodOf(p.method()) < 0) {
                // 码与消息对齐：这条分支是「支付方式不认识」，不是「渠道未启用」
                //（渠道未启用由 common-payment-channel 的 PAYMENT_CHANNEL_DISABLED 表达）。
                throw new ApiException(422, "PAYMENT_METHOD_UNKNOWN", "未知支付方式: " + p.method());
            }
            // 币种能力（规范 §2.2.3）：微信/支付宝仅 CNY、Stripe 以 USD 结算，不匹配在落库前就拒绝，
            // 绝不让用户走到渠道下单才失败（该错误与渠道自身的 PAYMENT_CHANNEL_DISABLED 职责不同）。
            PaymentChannelCurrencyCapability.requireSupported(p.method(), currencyCode);
            if (!paymentMethodService.isTenantAllowed(tenantId, storeId, p.method())) {
                throw new ApiException(422, "PAYMENT_METHOD_NOT_GRANTED", "支付方式未授权: " + p.method());
            }
            if (p.amount() > remaining) {
                throw new ApiException(422, "PAYMENT_AMOUNT_MISMATCH", "收款金额超过剩余应收");
            }
            remaining -= p.amount();
        }
        if (remaining != 0) {
            throw new ApiException(422, "PAYMENT_INCOMPLETE", "收款金额必须足额覆盖服务端应收");
        }
        return ordered;
    }

    private PayCollectPo newCollectRecord(Long tenantId, Long orderId, Long customerId, String currencyCode,
                                          long payable, List<PaymentItem> payments, String idempotencyKey) {
        PayCollectPo collect = new PayCollectPo();
        collect.setTenantId(tenantId);
        collect.setCollectNo(nextCollectNo());
        collect.setOrderId(orderId);
        collect.setIdempotencyKey(idempotencyKey);
        collect.setState(STATE_INIT);
        // 币种快照（§5）：分腿与找零的币种固化为列，便于对账按币种过滤（金额只在 request/response JSON 里）。
        collect.setCurrencyCode(currencyCode);
        collect.setRequestJson(toJson(new CollectRequestSnapshot(orderId, customerId, currencyCode, payable, payments)));
        collect.setCreatedAt(LocalDateTime.now());
        collect.setUpdatedAt(LocalDateTime.now());
        return collect;
    }

    /**
     * 收款币种归一与一致性校验（16_CURRENCY_CONVENTIONS §5/§6）：
     * <ul>
     *   <li>订单有币种快照时：请求币种必须与快照一致，不一致直接 422 {@code CURRENCY_MISMATCH}
     *       （跨币种收款是合规禁区，**禁止**任何汇率换算）；请求未带币种时按快照补齐，
     *       保证 pay_intent / pay_transaction / pay_collect 落库币种与订单一致。</li>
     *   <li>显式传入不受支持的币种代码 → 400 {@code CURRENCY_UNSUPPORTED}（非法值不得静默降级）。</li>
     *   <li>订单币种未知（历史/异常数据，或未注入账单查询）时取请求币种；仍为空则回退当时租户币种
     *       （缺省 USD），绝不写入空币种。</li>
     * </ul>
     */
    private static String resolveCollectCurrency(OrderBillingPo billing, String requested) {
        if (requested != null && !requested.isBlank() && !Currency.isSupported(requested)) {
            throw new ApiException(400, "CURRENCY_UNSUPPORTED", "不支持的币种: " + requested);
        }
        String orderCurrency = billing == null ? null : billing.getCurrencyCode();
        if (orderCurrency != null && !orderCurrency.isBlank()) {
            String normalizedOrder = Currency.parse(orderCurrency).code();
            if (requested != null && !requested.isBlank()
                    && !normalizedOrder.equals(Currency.parse(requested).code())) {
                throw new ApiException(422, "CURRENCY_MISMATCH", "收款币种必须与订单币种一致");
            }
            return normalizedOrder;
        }
        return requested == null || requested.isBlank()
                ? CurrencyResolver.currentCode()
                : Currency.parse(requested).code();
    }

    /**
     * 接管一条 FAILED 幂等记录（同键重跑）：CAS 抢占 FAILED→INIT 并清掉旧失败快照，
     * 抢占失败说明另一并发重试已接管，返回 false。
     */
    private boolean claimFailedRecord(PayCollectPo row) {
        UpdateWrapper<PayCollectPo> update = new UpdateWrapper<>();
        update.eq("id", row.getId())
                .eq("state", STATE_FAILED)
                .set("state", STATE_INIT)
                .set("response_json", null)
                .set("updated_at", LocalDateTime.now());
        if (payCollectMapper.update(null, update) != 1) {
            return false;
        }
        row.setState(STATE_INIT);
        row.setResponseJson(null);
        return true;
    }

    /**
     * 同键重跑前的补偿续做：上一次失败若未能把 customer 侧扣减归还干净，按原幂等键重放归还
     * （归还端点按幂等键幂等，重复调用不会重复入账）；仍失败或快照缺失（旧版本遗留记录）时拒绝重跑，
     * 避免在资金状态不明的情况下重复扣减。
     */
    private void settlePendingReleases(PayCollectPo existing, Long tenantId, Long storeId, Long orderId,
                                       Long customerId, String currencyCode) {
        FailureSnapshot snapshot = readFailure(existing.getResponseJson());
        if (snapshot == null || snapshot.attemptKey() == null) {
            log.error("组合收款失败记录缺少补偿快照，需人工核对后再重试: collectNo={} orderId={}",
                    existing.getCollectNo(), existing.getOrderId());
            throw new ApiException(409, "PAYMENT_RECONCILIATION_REQUIRED", "该收款单存在未结清的历史流水，请人工核对后再试");
        }
        List<HeldLeg> heldLegs = snapshot.heldLegs();
        if (snapshot.compensated() || heldLegs == null || heldLegs.isEmpty()) {
            return;
        }
        boolean compensated = releaseHeld(tenantId, storeId, orderId, customerId, currencyCode,
                snapshot.attemptKey(), heldLegs);
        if (!compensated) {
            log.error("组合收款失败补偿续做仍失败，拒绝重跑以避免重复扣减: collectNo={} orderId={} attemptKey={}",
                    existing.getCollectNo(), existing.getOrderId(), snapshot.attemptKey());
            throw new ApiException(409, "PAYMENT_RECONCILIATION_REQUIRED", "该收款单存在未结清的历史流水，请人工核对后再试");
        }
    }

    private void redeemPoints(Long tenantId, Long storeId, Long orderId, Long customerId, long points,
                              String attemptKey) {
        requireCustomer(customerId, LEG_POINT);
        customerClient.redeemPoints(tenantId, storeId, customerId, points, orderId,
                legIdempotencyKey(attemptKey, LEG_POINT));
    }

    private void deductWallet(Long tenantId, Long storeId, Long orderId, Long customerId, String currencyCode,
                              long amount, String attemptKey) {
        requireCustomer(customerId, LEG_WALLET);
        customerClient.deductWallet(tenantId, storeId, customerId, amount, currencyCode, orderId,
                legIdempotencyKey(attemptKey, LEG_WALLET));
    }

    /**
     * 现金/线上渠道差额：写支付意图 + 交易。调用方把它放在收款主事务内，
     * 与 ord_order 落账、pay_collect 状态机一起提交或一起回滚（不再使用独立事务）。
     *
     * @param legNo 本笔收款内现金类分腿的序号（从 1 起），用于派生唯一的幂等键
     */
    private void recordCash(Long tenantId, Long storeId, Long orderId, String currencyCode, long amount,
                            String attemptKey, String method, int legNo) {
        PayIntentPo po = new PayIntentPo();
        po.setTenantId(tenantId);
        po.setStoreId(storeId);
        po.setOrderId(orderId);
        po.setProvider(method);
        po.setPaymentMethod(method);
        po.setAmount(BigDecimal.valueOf(amount));
        po.setCurrencyCode(currencyCode);
        po.setStatus("SUCCEEDED");
        po.setIdempotencyKey(legIdempotencyKey(attemptKey, cashLegSuffix(legNo)));
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        payIntentMapper.insert(po);

        PayTransactionPo tx = new PayTransactionPo();
        tx.setTenantId(tenantId);
        tx.setPaymentIntentId(po.getId());
        tx.setProvider(method);
        tx.setAmount(BigDecimal.valueOf(amount));
        tx.setCurrencyCode(currencyCode);
        tx.setStatus("SUCCEEDED");
        tx.setOccurredAt(LocalDateTime.now());
        payTransactionMapper.insert(tx);
    }

    /**
     * 失败补偿：归还已扣减的积分/储值（反向顺序）。释放端点按幂等键幂等，重复调用不会重复归还；
     * 全部归还成功才返回 true（返回 false 表示资金状态不明，重跑前必须先续做补偿）。
     */
    private boolean releaseHeld(Long tenantId, Long storeId, Long orderId, Long customerId, String currencyCode,
                                String attemptKey, List<HeldLeg> heldLegs) {
        boolean allReleased = true;
        for (int i = heldLegs.size() - 1; i >= 0; i--) {
            HeldLeg leg = heldLegs.get(i);
            try {
                if (LEG_POINT.equals(leg.type())) {
                    customerClient.releasePoints(tenantId, storeId, customerId, leg.amount(), orderId,
                            legIdempotencyKey(attemptKey, "RELEASE:" + LEG_POINT));
                } else if (LEG_WALLET.equals(leg.type())) {
                    customerClient.releaseWallet(tenantId, storeId, customerId, leg.amount(), currencyCode, orderId,
                            legIdempotencyKey(attemptKey, "RELEASE:" + LEG_WALLET));
                }
            } catch (RuntimeException ex) {
                // 补偿失败不再吞掉：记录 ERROR（含订单/分项/金额/尝试键）并保留重试或人工介入路径，
                // 同时继续补偿其余分项，避免二次资金问题。
                allReleased = false;
                log.error("组合收款失败补偿未成功，需重试或人工介入: orderId={} leg={} amount={} attemptKey={}",
                        orderId, leg.type(), leg.amount(), attemptKey, ex);
            }
        }
        return allReleased;
    }

    private void requireCustomer(Long customerId, String method) {
        if (customerId == null) {
            throw new ApiException(400, "CUSTOMER_REQUIRED", "使用 " + method + " 抵扣必须指定会员");
        }
    }

    /** 分腿幂等键：收款单号 + 本次尝试随机段 + 分腿（长度可控，不会超过下游 64 字符的幂等键列）。 */
    private String newAttemptKey(String collectNo) {
        return collectNo + "#" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String legIdempotencyKey(String attemptKey, String leg) {
        return attemptKey + ":" + leg;
    }

    /**
     * 现金类分腿（CASH/ALIPAY/WECHAT/STRIPE）的幂等后缀：按分腿序号派生，如 {@code CASH:1}、{@code CASH:2}。
     *
     * <p>此前所有现金类分腿统一用固定后缀 {@code CASH}，一旦同一笔收款拆成两笔现金类分腿
     * （如 ALIPAY 3000 + CASH 5000），第二腿就会撞上生产唯一键
     * {@code uk_pay_intent_tenant_idem (tenant_id, idempotency_key)}，整笔收款回滚。
     * 序号保证同笔收款内各腿唯一；支付方式与金额已分别落在 provider/payment_method/amount 列，
     * 无需再进幂等键。长度 = attemptKey(≤32) + 6 + 序号(≤2) &lt; 64，不会超出 idempotency_key 的 varchar(64)。
     */
    private static String cashLegSuffix(int legNo) {
        return "CASH:" + legNo;
    }

    private PayCollectPo findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        LambdaQueryWrapper<PayCollectPo> qw = new LambdaQueryWrapper<>();
        qw.eq(PayCollectPo::getTenantId, tenantId)
          .eq(PayCollectPo::getIdempotencyKey, idempotencyKey)
          .last("LIMIT 1");
        return payCollectMapper.selectOne(qw);
    }

    private void markConfirmed(PayCollectPo collect, CollectResult result) {
        collect.setState(STATE_CONFIRMED);
        collect.setResponseJson(toJson(result));
        collect.setUpdatedAt(LocalDateTime.now());
        payCollectMapper.updateById(collect);
    }

    /** 失败快照落库（本地事务已回滚后独立提交）：code/message 供排查，attemptKey/heldLegs/compensated 供同键重跑续做。 */
    private void markFailed(PayCollectPo collect, RuntimeException ex, String attemptKey, List<HeldLeg> heldLegs,
                            boolean compensated) {
        int status = 500;
        String code = "COLLECT_FAILED";
        String message = "组合收款失败";
        if (ex instanceof ApiException apiEx) {
            status = apiEx.getStatus();
            code = apiEx.getCode();
            message = apiEx.getMessage();
        } else {
            log.error("组合收款未预期失败: collectNo={} orderId={}", collect.getCollectNo(), collect.getOrderId(), ex);
        }
        collect.setState(STATE_FAILED);
        collect.setResponseJson(toJson(new FailureSnapshot(status, code, message, attemptKey, compensated, heldLegs)));
        collect.setUpdatedAt(LocalDateTime.now());
        payCollectMapper.updateById(collect);
    }

    private CollectResult deserializeResult(String json) {
        try {
            return objectMapper.readValue(json, CollectResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法反序列化组合收款结果", e);
        }
    }

    /** 读取失败快照；无法解析（含旧版本仅有 status/code/message 的记录）返回 null。 */
    private FailureSnapshot readFailure(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FailureSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化组合收款快照", e);
        }
    }

    private String nextCollectNo() {
        return "PC" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }

    private static int methodPriority(PaymentItem p) {
        return methodOf(p.method());
    }

    private static int methodOf(String method) {
        if ("POINT".equals(method)) return METHOD_POINT;
        if ("WALLET".equals(method)) return METHOD_WALLET;
        if ("CASH".equals(method) || "ALIPAY".equals(method) || "WECHAT".equals(method) || "STRIPE".equals(method)) {
            return METHOD_CASH;
        }
        return -1;
    }

    /** 已扣减待补偿的跨服务分腿（随失败快照落库，供同键重跑续做补偿）。 */
    public record HeldLeg(String type, long amount) {}
    private record CollectRequestSnapshot(Long orderId, Long customerId, String currencyCode, long payable,
                                          List<PaymentItem> payments) {}
    private record FailureSnapshot(int status, String code, String message, String attemptKey, boolean compensated,
                                   List<HeldLeg> heldLegs) {}

    public record PaymentItem(String method, long amount) {}
    public record CollectedMethod(String method, long amount) {}
    public record CollectResult(long remainingAmount, List<CollectedMethod> collectedByMethod) {}

    /** 订单已收分项汇总（内部读接口返回体；{@code currencyCode} 为币种快照，混币种时 {@code mixedCurrency=true}）。 */
    public record OrderCollectedLeg(String method, long amount) {}
    public record OrderCollected(Long orderId, String currencyCode, boolean mixedCurrency, long totalCollected,
                                 long cash, long wallet, long points,
                                 List<OrderCollectedLeg> collectedByMethod) {}
}
