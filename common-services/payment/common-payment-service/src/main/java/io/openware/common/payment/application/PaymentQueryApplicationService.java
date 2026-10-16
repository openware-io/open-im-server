package io.openware.common.payment.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.payment.domain.closing.repository.DailyClosingRepository;
import io.openware.common.payment.domain.collect.model.PayCollect;
import io.openware.common.payment.domain.collect.repository.PayCollectRepository;
import io.openware.common.payment.domain.intent.model.PaymentIntent;
import io.openware.common.payment.domain.intent.repository.PaymentIntentRepository;
import io.openware.common.payment.domain.refund.model.Refund;
import io.openware.common.payment.domain.refund.repository.RefundRepository;
import io.openware.common.payment.domain.shift.repository.ShiftRepository;
import io.openware.common.payment.domain.transaction.model.PaymentTransaction;
import io.openware.common.payment.domain.transaction.repository.PaymentTransactionRepository;
import io.openware.infrastructure.time.TimeRangeParams;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.openware.infrastructure.currency.Currency;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 收银/支付/交班/日结/退款 只读查询应用服务。
 *
 * <p><b>统一时间区间口径</b>：每个列表都接受 {@code from}/{@code to} 的**闭区间**筛选，由控制器经
 * {@link TimeRangeParams#parse} 解析（日期形态收口：{@code from} → 当天 00:00:00.000、
 * {@code to} → 当天 23:59:59.999），本层只负责把它交给端口——不包函数、不用 {@code DATE()}，
 * 保持索引可用。为空 = 不筛。
 *
 * <p><b>业务时间列</b>：支付流水/退款用 {@code created_at}（发生时刻）；交班用 {@code opened_at}
 * （交班的业务时间，不是记录写入时间）；日结的营业日是 {@link java.time.LocalDate} 列
 * {@code business_date}，因此按「日期边界」比较（端点各取日期部分）。
 *
 * <p>持久化经领域端口；本层只做装配与快照解析。
 */
@Service
@Slf4j
public class PaymentQueryApplicationService {
    /** 已确认收款（钱确实收到）才计入收款明细；INIT/HOLD/FAILED 是尝试，不是收款。 */
    private static final String STATE_CONFIRMED = PayCollect.STATE_CONFIRMED;
    private static final String REFUND_STATUS_REFUNDED = "REFUNDED";

    /** 单次批量查询的订单数上限：超限由控制器拒绝，避免一次把全租户收款拉出来。 */
    public static final int MAX_ORDER_IDS = 100;

    private final ShiftRepository shiftRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final DailyClosingRepository dailyClosingRepository;
    private final RefundRepository refundRepository;
    private final PayCollectRepository payCollectRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    /**
     * 只读路径的 JSON 解析器：遇到不认识的字段（旧版本快照新增字段）必须跳过而不是抛错——
     * 核对历史收款时，一条脏快照不能把整页收款明细带崩（写路径的严格解析仍在收款服务里）。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PaymentQueryApplicationService(ShiftRepository shiftRepository,
                                          PaymentIntentRepository paymentIntentRepository,
                                          DailyClosingRepository dailyClosingRepository,
                                          RefundRepository refundRepository,
                                          PayCollectRepository payCollectRepository,
                                          PaymentTransactionRepository paymentTransactionRepository) {
        this.shiftRepository = shiftRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.dailyClosingRepository = dailyClosingRepository;
        this.refundRepository = refundRepository;
        this.payCollectRepository = payCollectRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
    }

    /** 交班列表（按交班时间 {@code opened_at} 闭区间筛选，时间倒序）。 */
    public List<ShiftDto> shifts(TimeRange range) {
        return shiftRepository.listByOpenedAtBetween(from(range), to(range)).stream()
                .map(ShiftDto::from).toList();
    }

    /** 支付流水列表（按支付发生时刻 {@code created_at} 闭区间筛选，时间倒序）。 */
    public List<PayIntentDto> payments(TimeRange range) {
        return paymentIntentRepository.listByCreatedAtBetween(from(range), to(range)).stream()
                .map(PayIntentDto::from).toList();
    }

    /**
     * 日结列表（按**营业日** {@code business_date} 闭区间筛选，时间倒序）。
     *
     * <p>{@code business_date} 是 {@link java.time.LocalDate} 列，与其它列表的 {@code DATETIME(3)}
     * 不同：这里取区间的**日期边界**（{@code from.toLocalDate()} / {@code to.toLocalDate()}）比较，
     * 于是「营业日 2026-09-30」既能被 {@code from=2026-09-30} 命中，也能被 {@code to=2026-09-30} 命中。
     */
    public List<DailyClosingDto> dailyClosings(TimeRange range) {
        return dailyClosingRepository.listByBusinessDateBetween(dateFrom(range), dateTo(range)).stream()
                .map(DailyClosingDto::from).toList();
    }

    /** 退款申请列表（按申请发生时刻 {@code created_at} 闭区间筛选，时间倒序）。 */
    public List<RefundDto> refundRequests(TimeRange range) {
        return refundRepository.listByCreatedAtBetween(from(range), to(range)).stream()
                .map(RefundDto::from).toList();
    }

    /**
     * 订单收款明细（批量，供订单管理 / 收银台核对组合支付）。
     *
     * <p>按订单返回：每一笔**已确认**收款（收款单号/币种/本次应收/收款时间/会员）与其**全部分腿**
     * （积分、储值、现金、支付宝、微信、Stripe 各占一行，绝不合并、绝不省略）、该订单的渠道支付流水
     * （含渠道交易号与状态）以及退款记录。**没有收款数据的订单不会出现在结果里**，
     * 调用方据此展示「未收款」，而不是把缺失当成 0 元。
     *
     * <p>租户隔离：显式带 {@code tenant_id} 条件（不依赖拦截器兜底），并且只读已确认收款；
     * 单条脏快照（{@code response_json} 解析不了）只跳过该条，不影响同订单其它收款与其它订单。
     */
    public List<OrderCollectionsDto> orderCollections(Long tenantId, List<Long> orderIds) {
        List<Long> ids = orderIds == null ? List.of()
                : orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (tenantId == null || ids.isEmpty()) {
            return List.of();
        }

        List<PayCollect> collects = payCollectRepository.listConfirmedByOrderIds(tenantId, ids);
        List<PaymentIntent> intents = paymentIntentRepository.listByOrderIds(tenantId, ids);
        List<PaymentTransaction> transactions = loadTransactions(tenantId, intents);
        List<Refund> refunds = refundRepository.listByOrderIds(tenantId, ids);

        Map<Long, List<PayCollect>> collectsByOrder = new LinkedHashMap<>();
        for (PayCollect collect : collects) {
            collectsByOrder.computeIfAbsent(collect.orderId(), key -> new ArrayList<>()).add(collect);
        }
        Map<Long, List<PaymentIntent>> intentsByOrder = new LinkedHashMap<>();
        for (PaymentIntent intent : intents) {
            intentsByOrder.computeIfAbsent(intent.orderId(), key -> new ArrayList<>()).add(intent);
        }
        Map<Long, List<PaymentTransaction>> transactionsByIntent = new LinkedHashMap<>();
        for (PaymentTransaction transaction : transactions) {
            transactionsByIntent.computeIfAbsent(transaction.paymentIntentId(), key -> new ArrayList<>())
                    .add(transaction);
        }
        Map<Long, List<Refund>> refundsByOrder = new LinkedHashMap<>();
        for (Refund refund : refunds) {
            refundsByOrder.computeIfAbsent(refund.orderId(), key -> new ArrayList<>()).add(refund);
        }

        // 结果顺序跟随请求顺序（列表页按当前页订单批量取，前端可直接按 orderId 索引）。
        List<OrderCollectionsDto> result = new ArrayList<>();
        for (Long orderId : ids) {
            List<PayCollect> orderCollects = collectsByOrder.getOrDefault(orderId, List.of());
            List<PaymentIntent> orderIntents = intentsByOrder.getOrDefault(orderId, List.of());
            List<Refund> orderRefunds = refundsByOrder.getOrDefault(orderId, List.of());
            if (orderCollects.isEmpty() && orderIntents.isEmpty() && orderRefunds.isEmpty()) {
                continue;
            }
            result.add(buildOrderCollections(orderId, orderCollects, orderIntents, transactionsByIntent,
                    orderRefunds));
        }
        return result;
    }

    private OrderCollectionsDto buildOrderCollections(Long orderId,
                                                      List<PayCollect> collects,
                                                      List<PaymentIntent> intents,
                                                      Map<Long, List<PaymentTransaction>> transactionsByIntent,
                                                      List<Refund> refunds) {
        Set<String> currencies = new LinkedHashSet<>();
        List<OrderCollectionsDto.Collection> collections = new ArrayList<>();
        long totalCollected = 0L;
        for (PayCollect collect : collects) {
            CollectSnapshot request = parseRequest(collect.requestJson());
            List<OrderCollectionsDto.Leg> legs = parseLegs(collect.responseJson());
            long legTotal = legs.stream().mapToLong(OrderCollectionsDto.Leg::amount).sum();
            long payable = request == null ? legTotal : request.payable();
            long collectedAmount = legTotal > 0 ? legTotal : payable;
            totalCollected += collectedAmount;
            currencies.add(currencyOf(collect.currencyCode()));
            collections.add(new OrderCollectionsDto.Collection(
                    collect.collectNo(),
                    currencyOf(collect.currencyCode()),
                    payable,
                    collect.createdAt(),
                    request == null ? null : request.customerId(),
                    legs.size() > 1,
                    legs));
        }

        List<OrderCollectionsDto.Transaction> transactions = new ArrayList<>();
        for (PaymentIntent intent : intents) {
            currencies.add(currencyOf(intent.currencyCode()));
            for (PaymentTransaction tx : transactionsByIntent.getOrDefault(intent.id(), List.of())) {
                currencies.add(currencyOf(tx.currencyCode()));
                transactions.add(new OrderCollectionsDto.Transaction(
                        tx.id(),
                        tx.provider() == null ? intent.provider() : tx.provider(),
                        tx.providerTransactionNo(),
                        OrderCollectionsDto.minor(tx.amount()),
                        currencyOf(tx.currencyCode()),
                        tx.status(),
                        tx.occurredAt()));
            }
        }

        List<OrderCollectionsDto.Refund> refundViews = new ArrayList<>();
        long refundedAmount = 0L;
        for (Refund refund : refunds) {
            currencies.add(currencyOf(refund.currencyCode()));
            long approved = OrderCollectionsDto.minor(refund.approvedAmount());
            long requested = OrderCollectionsDto.minor(refund.requestedAmount());
            if (REFUND_STATUS_REFUNDED.equals(refund.status())) {
                // 只有真正退出去的钱才算已退款；APPROVED 只是批准，金额仍看 approvedAmount。
                refundedAmount += approved > 0 ? approved : requested;
            }
            refundViews.add(new OrderCollectionsDto.Refund(
                    refund.id(),
                    refund.status(),
                    requested,
                    approved,
                    currencyOf(refund.currencyCode()),
                    refund.providerRefundNo(),
                    refund.reason(),
                    refund.createdAt(),
                    refund.updatedAt()));
        }

        // 币种：命中数据币种一致才给单一币种；否则只给标记，调用方不得跨币种相加。
        Set<String> distinct = currencies.stream().filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String currencyCode = distinct.size() == 1 ? distinct.iterator().next() : null;
        return new OrderCollectionsDto(orderId, currencyCode, distinct.size() > 1, totalCollected, refundedAmount,
                collections, transactions, refundViews);
    }

    private List<PaymentTransaction> loadTransactions(Long tenantId, List<PaymentIntent> intents) {
        List<Long> intentIds = intents.stream().map(PaymentIntent::id).filter(Objects::nonNull).distinct()
                .toList();
        if (intentIds.isEmpty()) {
            return List.of();
        }
        return paymentTransactionRepository.listByPaymentIntentIds(tenantId, intentIds);
    }

    /** 收款请求快照里本读模型需要的字段（认不出的字段忽略，历史快照也能读）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CollectSnapshot(Long orderId, Long customerId, String currencyCode, long payable) {}

    private CollectSnapshot parseRequest(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CollectSnapshot.class);
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("payment record could not be read", e);
            return null;
        }
    }

    /** 收款结果快照里的分腿（{@code collectedByMethod}）；解析不了或字段缺失返回空列表，绝不抛 500。 */
    private List<OrderCollectionsDto.Leg> parseLegs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        CollectApplicationService.CollectResult result;
        try {
            result = objectMapper.readValue(json, CollectApplicationService.CollectResult.class);
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("payment list could not be read", e);
            return List.of();
        }
        if (result == null || result.collectedByMethod() == null) {
            return List.of();
        }
        return result.collectedByMethod().stream()
                .filter(leg -> leg != null && leg.method() != null && leg.amount() > 0)
                .map(leg -> new OrderCollectionsDto.Leg(leg.method(), leg.amount()))
                .toList();
    }

    private static String currencyOf(String currencyCode) {
        return currencyCode == null || currencyCode.isBlank()
                ? null
                : Currency.parse(currencyCode).code();
    }

    private static java.time.LocalDateTime from(TimeRange range) {
        return range == null ? null : range.fromInclusive();
    }

    private static java.time.LocalDateTime to(TimeRange range) {
        return range == null ? null : range.toInclusive();
    }

    private static java.time.LocalDate dateFrom(TimeRange range) {
        return range == null || range.fromInclusive() == null ? null : range.fromInclusive().toLocalDate();
    }

    private static java.time.LocalDate dateTo(TimeRange range) {
        return range == null || range.toInclusive() == null ? null : range.toInclusive().toLocalDate();
    }
}
