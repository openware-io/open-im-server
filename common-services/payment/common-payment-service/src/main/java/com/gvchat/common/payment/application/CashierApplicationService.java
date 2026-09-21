package com.gvchat.common.payment.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.payment.infra.persistence.mapper.DailyClosingMapper;
import com.gvchat.common.payment.infra.persistence.mapper.PayIntentMapper;
import com.gvchat.common.payment.infra.persistence.mapper.PayCollectMapper;
import com.gvchat.common.payment.infra.persistence.mapper.RefundMapper;
import com.gvchat.common.payment.infra.persistence.mapper.ShiftMapper;
import com.gvchat.common.payment.infra.persistence.po.DailyClosingPo;
import com.gvchat.common.payment.infra.persistence.po.PayIntentPo;
import com.gvchat.common.payment.infra.persistence.po.ShiftPo;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 收银班次 + 日结（现金对账）。 */
@Service
public class CashierApplicationService {
    /** 现金支付方式/渠道代码（{@code pay_intent.provider}）。 */
    private static final String PROVIDER_CASH = "CASH";
    /** 收款只统计成功行，与交班现金对账（{@link PayIntentMapper#sumCashSucceeded}）同一状态过滤。 */
    private static final String INTENT_SUCCEEDED = "SUCCEEDED";
    /** 已交班班次状态：正常交班与长短款待复核（{@code pay_shift.status}）。 */
    private static final String SHIFT_CLOSED = "CLOSED";
    private static final String SHIFT_REVIEW_REQUIRED = "REVIEW_REQUIRED";
    /** 脏数据（provider 为空）在细分行里的归一值，避免写出 null 字段。 */
    private static final String PROVIDER_UNKNOWN = "UNKNOWN";

    private final ShiftMapper shiftMapper;
    private final DailyClosingMapper dailyClosingMapper;
    private final PayIntentMapper payIntentMapper;
    private final RefundMapper refundMapper;
    private final PayCollectMapper payCollectMapper;
    private final AuditClient auditClient;

    @org.springframework.beans.factory.annotation.Autowired
    public CashierApplicationService(ShiftMapper shiftMapper, DailyClosingMapper dailyClosingMapper,
                                     PayIntentMapper payIntentMapper, RefundMapper refundMapper,
                                     PayCollectMapper payCollectMapper, AuditClient auditClient) {
        this.shiftMapper = shiftMapper;
        this.dailyClosingMapper = dailyClosingMapper;
        this.payIntentMapper = payIntentMapper;
        this.refundMapper = refundMapper;
        this.payCollectMapper = payCollectMapper;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配/单测：不注入组合收款查询时，日结不含储值/积分分项（生产装配始终注入）。 */
    public CashierApplicationService(ShiftMapper shiftMapper, DailyClosingMapper dailyClosingMapper,
                                     PayIntentMapper payIntentMapper, RefundMapper refundMapper,
                                     AuditClient auditClient) {
        this(shiftMapper, dailyClosingMapper, payIntentMapper, refundMapper, null, auditClient);
    }

    /**
     * 开班：登记开班现金备付金并固化本班次的币种快照。
     *
     * <p><b>现金单位口径（冻结）</b>：{@code openingCash} 一律为<b>最小货币单位</b>
     * （CNY 分 / USD cent）的<b>非负整数</b>，与 {@code pay_intent.amount}、{@code actualCash}
     * 同一量级；<b>不做任何分/元换算</b>。null 视为 0；负数或带小数（非法分）返回
     * {@code 400 SHIFT_CASH_INVALID}（见 {@link #requireMinorUnitCash}）。
     */
    @Transactional
    public ShiftDto openShift(Long tenantId, Long storeId, Long terminalId, Long operatorId, BigDecimal openingCash) {
        // 金额单位校验（无状态前置）在留痕范围之外：参数非法不产生业务副作用，不为它写失败痕迹。
        BigDecimal cash = requireMinorUnitCash(openingCash, "开班现金");
        ShiftPo po = new ShiftPo();
        po.setTenantId(tenantId); po.setStoreId(storeId); po.setTerminalId(terminalId); po.setOperatorId(operatorId);
        po.setOpenedAt(LocalDateTime.now()); po.setOpeningCash(cash); po.setExpectedCash(BigDecimal.ZERO);
        // 币种快照（16_CURRENCY_CONVENTIONS §5/§6）：开班即固化本班次的现金盘点币种，
        // 之后租户改设置也不得重算历史班次的 expected_cash / difference_amount。
        po.setCurrencyCode(CurrencyResolver.currentCode());
        po.setStatus("OPEN"); po.setCreatedAt(LocalDateTime.now()); po.setUpdatedAt(LocalDateTime.now());
        try {
            shiftMapper.insert(po);
            // 开班此前完全没有留痕：班次是交班对账与现金责任的起点，必须可回溯。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .storeId(storeId)
                    .operatorId(operatorId)
                    .action("cashier.shift.open")
                    .actionLabel("开班")
                    .resourceType("pay_shift")
                    .resourceId(po.getId() == null ? null : String.valueOf(po.getId()))
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"storeId\":" + storeId + ",\"terminalId\":" + terminalId
                            + ",\"currencyCode\":" + jsonText(po.getCurrencyCode()) + "}")
                    .build());
            return ShiftDto.from(po);
        } catch (RuntimeException failure) {
            recordFailure("cashier.shift.open", "开班", tenantId, storeId, po.getId(), failure);
            throw failure;
        }
    }

    /**
     * 交班现金对账（KTV_BUSINESS_01 §7.6）：
     * expected_cash = opening_cash + Σ(班次内**同币种** CASH 收款)；difference_amount = actual_cash − expected_cash。
     * 现金全额收款且无长短款时 difference_amount = 0；现金退款冲减待 pay_refund 补门店/交班时间戳后接入。
     *
     * <p><b>币种（16_CURRENCY_CONVENTIONS §5/§6）</b>：本班次币种取开班时固化的快照 {@code pay_shift.currency_code}，
     * 现金汇总按它过滤（{@link PayIntentMapper#sumCashSucceeded}），**禁止**把不同币种的现金静默相加；
     * 快照为空（历史行）时按默认 USD 归一。
     *
     * <p><b>现金单位口径（冻结，不可再各自解释）</b>：{@code pay_intent.amount}、{@code pay_shift.opening_cash}
     * 与本方法的 {@code actualCash}（及 {@link #openShift} 的 {@code openingCash}）<b>一律是最小货币单位</b>
     * （CNY 分 / USD cent）的<b>非负整数</b>，三者可直接相加相减，<b>本方法不做任何分/元换算</b>。
     * 调用方（后台 {@code shift.vue} 的 {@code yuanToFen}、App 班次屏的 {@code parseMoneyInput}）负责在入口
     * 把「元」折算成最小单位后再提交。{@code actualCash} 为 null 视为 0；负数或带小数（非法分）返回
     * {@code 400 SHIFT_CASH_INVALID}（见 {@link #requireMinorUnitCash}）。
     */
    @Transactional
    public ShiftDto closeShift(Long shiftId, BigDecimal actualCash, String remark) {
        // 金额单位校验（无状态前置）在留痕范围之外。
        BigDecimal cash = requireMinorUnitCash(actualCash, "交班现金");
        ShiftPo po = shiftMapper.selectById(shiftId);
        try {
            if (po == null || !"OPEN".equals(po.getStatus())) {
                throw new IllegalStateException("SHIFT_ALREADY_OPEN");
            }
            LocalDateTime now = LocalDateTime.now();
            BigDecimal openingCash = po.getOpeningCash() == null ? BigDecimal.ZERO : po.getOpeningCash();
            po.setCurrencyCode(Currency.parse(po.getCurrencyCode()).code());
            BigDecimal expectedCash = openingCash.add(sumCashCollected(po, now));
            po.setClosedAt(now); po.setActualCash(cash); po.setExpectedCash(expectedCash);
            po.setDifferenceAmount(cash.subtract(expectedCash));
            po.setStatus("CLOSED"); po.setUpdatedAt(now);
            shiftMapper.updateById(po);
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(po.getTenantId())
                    .storeId(po.getStoreId())
                    .operatorId(po.getOperatorId())
                    .action("cashier.shift.close")
                    .actionLabel("交班结账")
                    .resourceType("pay_shift")
                    .resourceId(String.valueOf(po.getId()))
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"actualCash\":" + cash + ",\"expectedCash\":" + po.getExpectedCash()
                            + ",\"difference\":" + po.getDifferenceAmount()
                            + ",\"currencyCode\":\"" + po.getCurrencyCode() + "\"}")
                    .build());
            return ShiftDto.from(po);
        } catch (RuntimeException failure) {
            // 交班对账失败留痕（汇总读库失败/落库失败）：长短款直接关系现金责任，失败必须可回溯。
            recordFailure("cashier.shift.close", "交班结账", po.getTenantId(), po.getStoreId(), shiftId, failure);
            throw failure;
        }
    }

    /**
     * 现金金额单位校验（口径冻结的唯一入口）：一律最小货币单位（CNY 分 / USD cent）的<b>非负整数</b>，
     * null 视为 0；负数或带小数（即「非法分」，例如 100.5）返回 400 {@code SHIFT_CASH_INVALID}。
     *
     * <p>只拒绝<b>非整数</b>数值：{@code 100} 与等值写法 {@code 100.00} 都表示 100 分，视为合法；
     * 本方法<b>不做</b>任何分/元换算，因此绝不会把「元」静默当「分」或反之。
     *
     * @param amount 调用方提交的现金金额（可为 null）
     * @param label  中文口径名（「开班现金」/「交班现金」），仅用于错误消息
     * @return 归一化后的非负整数金额（null → 0）
     */
    private static BigDecimal requireMinorUnitCash(BigDecimal amount, String label) {
        if (amount == null) return BigDecimal.ZERO;
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new ApiException(400, "SHIFT_CASH_INVALID", label + "必须是不小于 0 的最小货币单位整数");
        }
        return amount;
    }

    private BigDecimal sumCashCollected(ShiftPo shift, LocalDateTime closedAt) {
        if (shift.getOpenedAt() == null || shift.getTenantId() == null || shift.getStoreId() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = payIntentMapper.sumCashSucceeded(
                shift.getTenantId(), shift.getStoreId(), Currency.parse(shift.getCurrencyCode()).code(),
                shift.getOpenedAt(), closedAt);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 营业日结：提交即按「门店 + 营业日」汇总**按币种分组**的明细并写入 {@code summary_json}，
     * 结构见 {@link DailyClosingSummary}（金额一律最小货币单位整数，禁止跨币种求和）。
     *
     * <p><b>储值币/积分收款（本改动新增，独立分项、只对新增数据生效、不回填历史）</b>：
     * A380 币（储值 WALLET）与积分（POINT）是组合支付里的**支付工具**（只有现金与价格才带币种），
     * 其抵扣不落 {@code pay_intent}，只在 {@code pay_collect.response_json} 的分腿里；日结把它们
     * **抵扣的账单金额**作为 {@code providers[]} 的独立分项（provider = {@code WALLET}/{@code POINT}）
     * 按币种并列展示，**不并入** {@code collectedAmount}；代币/积分**数量**不在本服务落库，不输出。
     * 历史日结行与历史 {@code summary_json} 原样保留、不回填，解析失败按 0 跳过。
     * 详细口径见 {@link #collectWalletAndPointCollections} 与 {@link DailyClosingSummary}。
     *
     * <p><b>营业日窗口（沿用既有实现，不新造口径）</b>：{@code [businessDate 00:00, businessDate+1 00:00)}，
     * 即自然日窗口；与订单域报表把 {@code businessDate} 折算成时间窗的既有做法一致
     * （{@code platform-order-service ReportController#ordersOfBusinessDate}：{@code businessDate.atStartOfDay()}
     * ~ {@code businessDate.plusDays(1).atStartOfDay()}），也与管理端报表按 {@code DATE_FORMAT(created_at,'%Y-%m-%d')}
     * 归日的日界一致。门店级「营业日切点」（SAAS_PLATFORM_01 §125）全仓尚未实现，故此处不引入。
     *
     * <p><b>门店维度与状态过滤（沿用既有实现）</b>：
     * <ul>
     *   <li><b>收款</b>：{@code pay_intent}（tenant_id + store_id + status='SUCCEEDED' + created_at ∈ 窗口）。
     *       与交班 {@link PayIntentMapper#sumCashSucceeded}（{@code PayIntentMapper:22-28}）同一张表、同一状态
     *       过滤、同一窗口语义，所以日结的现金合计 == 当日各班次现金收款合计；按 {@code currency_code} +
     *       {@code provider}（支付方式/渠道）细分。</li>
     *   <li><b>退款</b>：{@code pay_refund} 终态 {@code REFUNDED}，门店经 {@code ord_order.store_id} 定位，
     *       归属时间取 {@code updated_at}（退款完成时间），见
     *       {@link RefundMapper#sumRefundedByCurrency}。</li>
     *   <li><b>储值币/积分</b>：{@code pay_collect} 成功终态 {@code CONFIRMED} 的
     *       {@code response_json} 分腿（{@code WALLET}/{@code POINT}），门店经 {@code ord_order.store_id}
     *       定位，归属时间取 {@code created_at}，见
     *       {@link #collectWalletAndPointCollections}。二者是组合支付里的**支付工具**（不是货币），
     *       这里只汇总其**抵扣的账单金额**（货币、按币种分组），**不并入** {@code collectedAmount}
     *       （后者仍是现金类渠道合计，与对账口径一致），而是作为 {@code providers[]} 的独立分项并列展示，
     *       便于运营按「支付构成 = 现金类合计 + 储值币抵扣 + 积分抵扣」核对；代币/积分**数量**不在本服务
     *       落库，故不输出、也不反推。</li>
     *   <li><b>交班长短款</b>：{@code pay_shift} 已交班班次（CLOSED/REVIEW_REQUIRED），归属时间取
     *       {@code closed_at}，币种取班次自身快照 {@code pay_shift.currency_code}——改租户币种设置不得重算
     *       历史班次的长短款。</li>
     * </ul>
     *
     * <p><b>幂等/重复日结（沿用既有实现）</b>：每次提交都按当时数据重新计算汇总后 insert；同一
     * (tenant_id, store_id, business_date) 由唯一键 {@code uk_pay_daily_tenant_store_date} 拒绝重复提交
     * ——本方法不吞异常、也不改成覆盖更新，因此重复提交仍被拒绝，且被拒绝那次算出的汇总与首次一致。
     * 历史行（本改动之前提交的日结）{@code summary_json} 为 null，不回填。
     *
     * @param businessDate 营业日；为 null 时返回 400 {@code DAILY_CLOSING_DATE_REQUIRED}（不再 NPE 成 500）
     */
    @Transactional
    public DailyClosingDto submitDailyClosing(Long tenantId, Long storeId, LocalDate businessDate, Long submittedBy) {
        if (businessDate == null) {
            throw new ApiException(400, "DAILY_CLOSING_DATE_REQUIRED", "营业日不能为空");
        }
        try {
            DailyClosingSummary summary = summarizeBusinessDate(tenantId, storeId, businessDate);
            DailyClosingPo po = new DailyClosingPo();
            po.setTenantId(tenantId); po.setStoreId(storeId); po.setBusinessDate(businessDate); po.setSubmittedBy(submittedBy);
            // 币种快照：单币种营业日以该币种出具；混币种（summary.currencyCode=null）或空营业日保留当时租户币种，
            // 混币种由 summary.mixedCurrency 显式标注，调用方不得把它当成单币种金额使用。
            po.setCurrencyCode(summary.currencyCode() == null ? CurrencyResolver.currentCode() : summary.currencyCode());
            po.setSummaryJson(summary.toJson());
            po.setStatus("SUBMITTED"); po.setCreatedAt(LocalDateTime.now()); po.setUpdatedAt(LocalDateTime.now());
            dailyClosingMapper.insert(po);
            // 日结此前完全没有留痕：营业日结是财务对账与营业数据出具的唯一入口，必须可回溯。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .tenantId(tenantId)
                    .storeId(storeId)
                    .operatorId(submittedBy)
                    .action("cashier.daily_closing.close")
                    .actionLabel("营业日结")
                    .resourceType("pay_daily_closing")
                    .resourceId(po.getId() == null ? null : String.valueOf(po.getId()))
                    .result(AuditClient.AuditRecord.RESULT_SUCCESS)
                    .detailJson("{\"businessDate\":\"" + businessDate + "\",\"storeId\":" + storeId
                            + ",\"mixedCurrency\":" + summary.mixedCurrency() + "}")
                    .build());
            return DailyClosingDto.from(po);
        } catch (RuntimeException failure) {
            // 日结失败留痕（汇总读库失败/重复提交撞唯一键/落库失败）：与成功同码。
            recordFailure("cashier.daily_closing.close", "营业日结", tenantId, storeId, null, failure);
            throw failure;
        }
    }

    /**
     * 收银班次/日结写操作失败留痕：动作码与成功路径同码，{@code result=FAILURE} + 稳定 errorCode
     * （{@link ApiException}/{@link BusinessException} 业务码优先，退化到异常类名并按列宽 64 截断）。
     *
     * <p>审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出：留痕不改变业务结果。
     * 不带幂等键（重复失败各自留痕，也不覆盖成功路径的稳定键）；
     * detail 只放班次/门店/营业日标识，<b>不含</b>现金金额与长短款金额。
     */
    private void recordFailure(String action, String actionLabel, Long tenantId, Long storeId, Long shiftId,
                               RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .action(action)
                .actionLabel(actionLabel)
                .resourceType("pay_shift")
                .resourceId(shiftId == null ? null : String.valueOf(shiftId))
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"shiftId\":" + shiftId + ",\"storeId\":" + storeId + "}")
                .build());
    }

    /** 最小 JSON 字符串转义（币种代码等字段未转义会拼出非法 JSON 丢审计）。 */
    private static String jsonText(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 汇总一个营业日：按币种归集收款（含现金细分）、退款与交班长短款。
     *
     * <p>币种一律取各记录的**币种快照**（{@link Currency#parse} 归一，空/非法回退默认币种），
     * 不使用当前租户设置，避免改设置后重算历史。
     */
    private DailyClosingSummary summarizeBusinessDate(Long tenantId, Long storeId, LocalDate businessDate) {
        LocalDateTime from = businessDate.atStartOfDay();
        LocalDateTime to = businessDate.plusDays(1).atStartOfDay();
        Map<String, CurrencyBucket> byCurrency = new TreeMap<>();
        collectCollections(byCurrency, tenantId, storeId, from, to);
        collectWalletAndPointCollections(byCurrency, tenantId, storeId, from, to);
        collectRefunds(byCurrency, tenantId, storeId, from, to);
        collectShiftDifferences(byCurrency, tenantId, storeId, from, to);
        if (byCurrency.isEmpty()) {
            // 空营业日（无收款/退款/交班）：产出一条全 0 分组，币种取当时租户币种，保证结构完整且不写 null。
            String fallback = CurrencyResolver.currentCode();
            byCurrency.put(fallback, new CurrencyBucket(fallback));
        }
        List<DailyClosingSummary.CurrencyLine> lines = new ArrayList<>();
        for (CurrencyBucket bucket : byCurrency.values()) {
            lines.add(bucket.toLine());
        }
        boolean mixedCurrency = lines.size() > 1;
        String currencyCode = mixedCurrency ? null : lines.get(0).currencyCode();
        return new DailyClosingSummary(businessDate.toString(), storeId, currencyCode, mixedCurrency, List.copyOf(lines));
    }

    /** 收款汇总：窗口内**成功**的 {@code pay_intent}，按币种 + provider（支付方式/渠道）分组。 */
    private void collectCollections(Map<String, CurrencyBucket> byCurrency, Long tenantId, Long storeId,
                                    LocalDateTime from, LocalDateTime to) {
        List<PayIntentPo> intents = payIntentMapper.selectList(new LambdaQueryWrapper<PayIntentPo>()
                .eq(PayIntentPo::getTenantId, tenantId).eq(PayIntentPo::getStoreId, storeId)
                .eq(PayIntentPo::getStatus, INTENT_SUCCEEDED)
                .ge(PayIntentPo::getCreatedAt, from).lt(PayIntentPo::getCreatedAt, to));
        if (intents == null) {
            return;
        }
        for (PayIntentPo intent : intents) {
            if (intent == null) {
                continue;
            }
            long amount = requireMinorAmount(intent.getAmount(), "pay_intent#" + intent.getId());
            String provider = intent.getProvider() == null || intent.getProvider().isBlank()
                    ? PROVIDER_UNKNOWN : intent.getProvider();
            CurrencyBucket bucket = bucketOf(byCurrency, intent.getCurrencyCode());
            bucket.collectionCount++;
            bucket.collectedAmount += amount;
            bucket.addProviderAmount(provider, amount);
            if (PROVIDER_CASH.equals(provider)) {
                bucket.cashCount++;
                bucket.cashAmount += amount;
            }
        }
    }

    /**
     * 储值币（WALLET，A380 币）/积分（POINT）收款汇总：作为**独立分项**，只对新增数据生效、不回填历史。
     *
     * <p><b>数据源与口径</b>：这类抵扣不落 {@code pay_intent}（{@link CollectApplicationService#recordCash}
     * 只对现金类分腿写 pay_intent/pay_transaction），实际落在 {@code pay_collect.response_json} 的分腿记录里，
     * 门店经 {@code ord_order.store_id} 回查（见 {@link PayCollectMapper#selectConfirmedByStoreAndWindow}）。
     * 只统计 {@code state='CONFIRMED'} 的组合收款，窗口与收款侧 {@code pay_intent} 同为
     * {@code [businessDate 00:00, businessDate+1 00:00)}。
     *
     * <p><b>语义（储值币/积分是支付工具，不是货币）</b>：这里只汇总分腿里的**抵扣账单金额**
     * （货币，最小货币单位整数，随该笔收款的币种快照归组）——它与现金合计一起构成「支付构成」，
     * 因此「支付构成 = {@code collectedAmount} + 储值币抵扣金额 + 积分抵扣金额」。
     * 该数值**不并入** {@code collectedAmount}/{@code cashAmount}（后者仍是现金类渠道合计，
     * 与对账/交班口径一致），只作为 {@code providers[]} 的 {@code WALLET}/{@code POINT} 独立行并列展示。
     *
     * <p><b>不输出代币/积分数量</b>：{@code response_json} 的分腿只有 {@code method} + 一个数值，
     * 没有独立的数量字段；数量属于 customer 域账本（{@code cst_wallet_ledger}/{@code cst_point_ledger}），
     * 本服务既拿不到也不反推，因此不写入任何数量字段（不套 {@code currencyCode}、不参与货币合计）。
     * 将来若在本服务落库独立数量，应新增 {@code walletTokens}/{@code points} 这类非 {@code Amount} 字段。
     *
     * <p><b>币种</b>：抵扣账单金额取该笔收款的币种快照（{@code pay_collect.currency_code}，等于订单币种），
     * 按币种归入各自分组，**禁止**跨币种求和。
     *
     * <p><b>容错</b>：{@code response_json} 为空/损坏/字段缺失时跳过并计 0，绝不抛错
     * （解析见 {@link CollectResponseLegs}）。
     */
    private void collectWalletAndPointCollections(Map<String, CurrencyBucket> byCurrency, Long tenantId,
                                                  Long storeId, LocalDateTime from, LocalDateTime to) {
        if (payCollectMapper == null) {
            return;
        }
        List<PayCollectMapper.ConfirmedCollectRow> rows =
                payCollectMapper.selectConfirmedByStoreAndWindow(tenantId, storeId, from, to);
        if (rows == null) {
            return;
        }
        for (PayCollectMapper.ConfirmedCollectRow row : rows) {
            if (row == null) {
                continue;
            }
            // 逐行解析：单行损坏只跳过该行，不影响其余收款与整份日结。
            List<CollectResponseLegs.WalletPointLeg> legs =
                    CollectResponseLegs.walletAndPointLegs(row.getResponseJson());
            if (legs.isEmpty()) {
                continue;
            }
            CurrencyBucket bucket = bucketOf(byCurrency, row.getCurrencyCode());
            for (CollectResponseLegs.WalletPointLeg leg : legs) {
                bucket.addProviderAmount(leg.method().toUpperCase(java.util.Locale.ROOT), leg.amount());
            }
        }
    }

    /** 退款汇总：窗口内已完成（REFUNDED）的退款，按退款自身币种快照分组、门店经订单定位。 */
    private void collectRefunds(Map<String, CurrencyBucket> byCurrency, Long tenantId, Long storeId,
                                LocalDateTime from, LocalDateTime to) {
        List<RefundMapper.RefundCurrencyAggregate> rows = refundMapper.sumRefundedByCurrency(tenantId, storeId, from, to);
        if (rows == null) {
            return;
        }
        for (RefundMapper.RefundCurrencyAggregate row : rows) {
            if (row == null) {
                continue;
            }
            CurrencyBucket bucket = bucketOf(byCurrency, row.getCurrencyCode());
            bucket.refundCount += row.getRefundCount();
            bucket.refundAmount += requireMinorAmount(row.getRefundAmount(), "pay_refund");
        }
    }

    /** 交班长短款：窗口内交班的班次，按班次币种快照分组求和（同币种才相加，长短款可为负）。 */
    private void collectShiftDifferences(Map<String, CurrencyBucket> byCurrency, Long tenantId, Long storeId,
                                         LocalDateTime from, LocalDateTime to) {
        // 已交班状态用 (status = CLOSED OR status = REVIEW_REQUIRED) 表达：语义与 IN 等价，
        // 但不会像 in(...) 那样在构造条件时就解析列名（纯 Mockito 单测里没有 MyBatis-Plus 元数据）。
        List<ShiftPo> shifts = shiftMapper.selectList(new LambdaQueryWrapper<ShiftPo>()
                .eq(ShiftPo::getTenantId, tenantId).eq(ShiftPo::getStoreId, storeId)
                .and(status -> status.eq(ShiftPo::getStatus, SHIFT_CLOSED)
                        .or().eq(ShiftPo::getStatus, SHIFT_REVIEW_REQUIRED))
                .ge(ShiftPo::getClosedAt, from).lt(ShiftPo::getClosedAt, to));
        if (shifts == null) {
            return;
        }
        for (ShiftPo shift : shifts) {
            if (shift == null) {
                continue;
            }
            CurrencyBucket bucket = bucketOf(byCurrency, shift.getCurrencyCode());
            bucket.shiftCount++;
            bucket.shiftDifferenceAmount += requireMinorAmount(shift.getDifferenceAmount(), "pay_shift#" + shift.getId());
        }
    }

    /** 取（或新建）某币种的分组桶；币种空/非法按既有口径归一为默认币种。 */
    private static CurrencyBucket bucketOf(Map<String, CurrencyBucket> byCurrency, String rawCurrencyCode) {
        String currencyCode = Currency.parse(rawCurrencyCode).code();
        return byCurrency.computeIfAbsent(currencyCode, CurrencyBucket::new);
    }

    /**
     * 汇总金额单位校验（与 {@link #requireMinorUnitCash} 同一冻结口径）：必须是最小货币单位
     * （CNY 分 / USD cent）整数；{@code null}（未写入/历史可空列）视为 0，带小数（非法分）时
     * **拒绝而不是静默取整**，返回 400 {@code DAILY_CLOSING_AMOUNT_INVALID}。
     *
     * @param amount 源记录金额（可为 null）
     * @param source 出错时定位用的来源标识（表名 + 主键）
     */
    private static long requireMinorAmount(BigDecimal amount, String source) {
        if (amount == null) {
            return 0L;
        }
        try {
            return amount.longValueExact();
        } catch (ArithmeticException ex) {
            throw new ApiException(400, "DAILY_CLOSING_AMOUNT_INVALID", "日结汇总金额必须是最小货币单位整数: " + source);
        }
    }

    /** 单币种汇总累加器（{@link DailyClosingSummary} 的 record 不可变，构建期用可变桶，最后一次性转行）。 */
    private static final class CurrencyBucket {
        private final String currencyCode;
        private final Map<String, ProviderBucket> providers = new TreeMap<>();
        private long collectionCount;
        private long collectedAmount;
        private long cashCount;
        private long cashAmount;
        private long refundCount;
        private long refundAmount;
        private long shiftCount;
        private long shiftDifferenceAmount;

        private CurrencyBucket(String currencyCode) {
            this.currencyCode = currencyCode;
        }

        private void addProviderAmount(String provider, long amount) {
            providers.computeIfAbsent(provider, ProviderBucket::new).add(amount);
        }

        private DailyClosingSummary.CurrencyLine toLine() {
            List<DailyClosingSummary.ProviderLine> lines = new ArrayList<>();
            for (ProviderBucket provider : providers.values()) {
                lines.add(new DailyClosingSummary.ProviderLine(provider.provider, provider.count, provider.amount));
            }
            return new DailyClosingSummary.CurrencyLine(currencyCode, collectionCount, collectedAmount, cashCount,
                    cashAmount, refundCount, refundAmount, shiftCount, shiftDifferenceAmount, List.copyOf(lines));
        }
    }

    /** 单币种内按支付方式/渠道的累加器。 */
    private static final class ProviderBucket {
        private final String provider;
        private long count;
        private long amount;

        private ProviderBucket(String provider) {
            this.provider = provider;
        }

        private void add(long value) {
            count++;
            amount += value;
        }
    }
}
