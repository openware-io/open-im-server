package io.openware.platform.customer.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository.LedgerPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 积分账户应用服务（流水只追加，扣减前校验余额）。
 * 积分调整：entry_type = ADJUST，points 可正可负，命令幂等 commandId。
 *
 * <p><b>余额写入一律走 CAS</b>：{@code cst_point_account} 只有 {@code version} 这一个并发保护手段
 * （PO 上没有 {@code @Version}，工程里也没有 {@code OptimisticLockerInnerInterceptor}），
 * 所以「读余额 → 判定够不够 → 写回」必须由单条 {@code ... WHERE id = ? AND version = ?} 完成
 * （见 {@link PointAccountRepository#updateBalanceWithVersion}）。受影响 0 行 = 期间已被他人写入，
 * 回 409 {@code POINT_ACCOUNT_VERSION_CONFLICT}，<b>绝不降级为无条件写入</b>。
 *
 * <p><b>写入顺序是刻意的：先 CAS 成功，再追加流水</b>（见 {@link #applyBalance}）。
 * 反过来在 CAS 失败时会留下「积分动过」的流水而余额没动，而<b>对账只能相信流水</b>；
 * 迁移前靠外层 {@code @Transactional} 回滚兜住，一旦有人把调用挪出事务就会变成静默的账实不符。
 */
@Service
public class PointApplicationService {
    private final PointAccountRepository repository;
    private final AuditClient auditClient;
    private final CustomerEventOutbox eventOutbox;
    private final PointRuleConfigApplicationService ruleService;

    public PointApplicationService(PointAccountRepository repository, AuditClient auditClient) {
        this(repository, auditClient, CustomerEventOutbox.disabled(), null);
    }

    @Autowired
    public PointApplicationService(PointAccountRepository repository, AuditClient auditClient,
                                   CustomerEventOutbox eventOutbox,
                                   PointRuleConfigApplicationService ruleService) {
        this.repository = repository;
        this.auditClient = auditClient;
        this.eventOutbox = eventOutbox;
        this.ruleService = ruleService;
    }

    public PointApplicationService(PointAccountRepository repository, AuditClient auditClient,
                                   CustomerEventOutbox eventOutbox) {
        this(repository, auditClient, eventOutbox, null);
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public PointApplicationService(PointAccountRepository repository) {
        this(repository, AuditClient.disabled(), CustomerEventOutbox.disabled(), null);
    }

    /** 会员积分视图：积分账户 + 账本分页（均为「个数」数量口径，响应不含币种与代币字段）。 */
    public MemberPointsData view(Long memberId, long page, long pageSize) {
        return view(memberId, null, page, pageSize);
    }

    /**
     * 流水读范围：总部不传门店看租户全量，门店上下文只看本店发生的流水。
     *
     * <p>账户不存在 → 404 {@code POINT_ACCOUNT_NOT_FOUND}（既有语义：与钱包的懒初始化不同，
     * 积分账户由 {@code ensureAccount} 在 C 端入口显式建出，读流水不属于"懒创建"路径）。
     */
    public MemberPointsData view(Long memberId, Long storeId, long page, long pageSize) {
        PointAccount account = requireAccountByMember(memberId);
        long safePage = page < 1 ? 1 : page;
        long safeSize = pageSize < 1 ? 1 : pageSize;
        LedgerPage ledger = repository.listLedger(account.id(), storeId, (safePage - 1) * safeSize, safeSize);
        return new MemberPointsData(account, ledger, safePage, safeSize);
    }

    /** 积分调整（ADJUST，命令幂等 commandId），高风险需审计。 */
    @Transactional
    public PointAccount adjust(Long memberId, Long points, String reason, String commandId) {
        return adjust(null, memberId, points, reason, commandId);
    }

    @Transactional
    public PointAccount adjust(Long storeId, Long memberId, Long points, String reason, String commandId) {
        try {
            if (points == null || points == 0) {
                throw new ApiException(400, "POINTS_INVALID", "调整积分不能为0");
            }
            if (commandId == null || commandId.isBlank()) {
                throw new ApiException(400, "COMMAND_ID_REQUIRED", "缺少 commandId");
            }
            PointAccount account = requireOrCreateAccount(memberId);
            assertIdempotent(commandId);
            if (points < 0 && !account.canCover(-points)) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "积分余额不足");
            }
            long balanceAfter = account.available() + points;
            PointAccount saved = applyBalance(account, balanceAfter, storeId,
                    PointLedgerEntry.TYPE_ADJUST, points, balanceAfter, null, null, commandId);
            // 积分属于会员资产：人工调整必须留痕（原因 + 变更前后余额），命令号即幂等键。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("points.adjust")
                    .resourceType("cst_point_account").resourceId(String.valueOf(saved.id()))
                    .operatorName(null)
                    .idempotencyKey(commandId)
                    .detailJson("{\"memberId\":" + memberId + ",\"points\":" + points + ",\"balanceAfter\":"
                            + balanceAfter + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "\\\""))
                            + "\"}")
                    .build());
            return saved;
        } catch (RuntimeException failure) {
            // 失败出口（参数非法/账户缺失/幂等冲突/余额不足/落库失败）必须留痕：审计只 WARN，异常原样抛出。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("points.adjust")
                    .resourceType("cst_point_account")
                    .resourceId(memberId == null ? null : String.valueOf(memberId))
                    .result(AuditClient.AuditRecord.RESULT_FAILURE)
                    .errorCode(AuditErrorCodes.of(failure))
                    .detailJson("{\"memberId\":" + memberId + ",\"points\":" + points + "}")
                    .build());
            throw failure;
        }
    }

    /**
     * 积分抵扣（组合收款，账本 REDEEM，幂等）；扣减前校验余额，不足拒绝（LEDGER_INSUFFICIENT）。
     * 先冻结后扣减占位：真实实现 HOLD（available→frozen）→ 结算成功 REDEEM（frozen 扣除）→ 失败 RELEASE（frozen→available）。
     */
    @Transactional
    public PointAccount redeem(Long memberId, Long points, Long orderId, String idempotencyKey) {
        return redeem(null, memberId, points, orderId, idempotencyKey);
    }

    @Transactional
    public PointAccount redeem(Long storeId, Long memberId, Long points, Long orderId, String idempotencyKey) {
        if (points == null || points <= 0) {
            throw new ApiException(400, "POINTS_INVALID", "抵扣积分必须为正整数");
        }
        if (ruleService != null) {
            TenantContext context = TenantContextHolder.get();
            long tenantId = context == null ? 0L : context.tenantId();
            PointRuleConfigApplicationService.PointRule rule = ruleService.resolve(tenantId, storeId,
                    context == null ? null : context.businessType());
            if (rule.redeemCapPoints() > 0 && points > rule.redeemCapPoints()) {
                throw new ApiException(422, "POINT_REDEEM_CAP_EXCEEDED", "本次抵扣积分超过规则上限");
            }
        }
        assertIdempotent(idempotencyKey);
        PointAccount account = requireAccountByMember(memberId);
        if (account.available() < points) {
            throw new ApiException(422, "LEDGER_INSUFFICIENT", "积分余额不足");
        }
        long balanceAfter = account.available() - points;
        return applyBalance(account, balanceAfter, storeId, PointLedgerEntry.TYPE_REDEEM, -points, balanceAfter,
                "ORDER", orderId, idempotencyKey);
    }

    /** 积分释放（组合收款失败补偿，账本 REVERSE 反向流水，幂等）：归还已抵扣的积分。真实 HOLD 语义下为 frozen→available。 */
    @Transactional
    public PointAccount release(Long memberId, Long points, Long orderId, String idempotencyKey) {
        return release(null, memberId, points, orderId, idempotencyKey);
    }

    @Transactional
    public PointAccount release(Long storeId, Long memberId, Long points, Long orderId, String idempotencyKey) {
        if (points == null || points <= 0) {
            throw new ApiException(400, "POINTS_INVALID", "释放积分必须为正整数");
        }
        if (repository.ledgerExists(idempotencyKey)) {
            return requireAccountByMember(memberId);
        }
        PointAccount account = requireAccountByMember(memberId);
        long balanceAfter = account.available() + points;
        return applyBalance(account, balanceAfter, storeId, PointLedgerEntry.TYPE_REVERSE, points, balanceAfter,
                "ORDER", orderId, idempotencyKey);
    }

    /** 积分余额（组合收款抵扣前校验）。账户不存在 → 404 {@code POINT_ACCOUNT_NOT_FOUND}（既有语义）。 */
    public PointAccount balance(Long memberId) {
        return requireAccountByMember(memberId);
    }

    /** 确保会员积分账户存在（C 端懒创建），默认积分计划 1。 */
    public PointAccount ensureAccount(Long customerId) {
        if (customerId == null) {
            throw new ApiException(400, "CUSTOMER_ID_REQUIRED", "缺少 customerId");
        }
        return repository.findByCustomer(customerId)
                .orElseGet(() -> repository.insert(PointAccount.open(customerId)));
    }

    private PointAccount requireAccountByMember(Long memberId) {
        return repository.findByCustomer(memberId)
                .orElseThrow(() -> new ApiException(404, "POINT_ACCOUNT_NOT_FOUND", "积分账户不存在"));
    }

    /** 支付确认后的积分获得入口；倍率、规则快照和流水失效时间均由 Customer 域决定。 */
    @Transactional
    public PointAccount earn(Long storeId, Long memberId, long eligibleAmountMinor,
                             Long orderId, String idempotencyKey) {
        if (eligibleAmountMinor <= 0) throw new ApiException(400, "EARN_AMOUNT_INVALID", "可积分金额必须为正数");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ApiException(400, "COMMAND_ID_REQUIRED", "缺少积分获得幂等键");
        if (repository.ledgerExists(idempotencyKey)) return requireOrCreateAccount(memberId);
        TenantContext context = TenantContextHolder.get();
        long tenantId = context == null ? 0L : context.tenantId();
        PointRuleConfigApplicationService.PointRule rule = ruleService == null
                ? PointRuleConfigApplicationService.PointRule.DEFAULT
                : ruleService.resolve(tenantId, storeId, context == null ? null : context.businessType());
        long points = BigDecimal.valueOf(eligibleAmountMinor).multiply(rule.earnRate())
                .setScale(0, RoundingMode.DOWN).longValueExact();
        PointAccount account = requireOrCreateAccount(memberId);
        if (points <= 0) return account;
        long balanceAfter = account.available() + points;
        String snapshot = "{\"earnRate\":" + rule.earnRate() + ",\"expiryDays\":" + rule.expiryDays()
                + ",\"businessType\":\"" + rule.businessType() + "\"}";
        return applyBalance(account, balanceAfter, storeId, PointLedgerEntry.TYPE_EARN, points, balanceAfter,
                "ORDER", orderId, idempotencyKey, snapshot, rule.expiryDays());
    }

    /** 收款确认事实消费的积分获得命令；payload 由消息消费者解析后调用。 */
    public PointAccount earnFromConfirmedCollection(Long storeId, Long memberId, long collectedAmount,
                                                    Long orderId, String eventId) {
        return earn(storeId, memberId, collectedAmount, orderId, "payment-collect-earn:" + eventId);
    }

    /** 积分抵扣金额换算：金额为最小货币单位，返回应扣积分数，向上取整避免少扣。 */
    public long pointsForAmount(Long storeId, long amountMinor) {
        if (amountMinor <= 0) throw new ApiException(400, "AMOUNT_INVALID", "抵扣金额必须为正数");
        TenantContext context = TenantContextHolder.get();
        PointRuleConfigApplicationService.PointRule rule = ruleService == null
                ? PointRuleConfigApplicationService.PointRule.DEFAULT
                : ruleService.resolve(context == null ? 0L : context.tenantId(), storeId,
                        context == null ? null : context.businessType());
        return BigDecimal.valueOf(amountMinor).divide(rule.redeemRate(), 0, RoundingMode.CEILING).longValueExact();
    }

    /** 积分可抵扣金额换算：返回 points 对应的最小货币单位，向下取整，避免多抵扣。 */
    public long amountForPoints(Long storeId, long points) {
        if (points <= 0) throw new ApiException(400, "POINTS_INVALID", "积分必须为正数");
        TenantContext context = TenantContextHolder.get();
        PointRuleConfigApplicationService.PointRule rule = ruleService == null
                ? PointRuleConfigApplicationService.PointRule.DEFAULT
                : ruleService.resolve(context == null ? 0L : context.tenantId(), storeId,
                        context == null ? null : context.businessType());
        return BigDecimal.valueOf(points).multiply(rule.redeemRate())
                .setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private PointAccount requireOrCreateAccount(Long memberId) {
        try { return requireAccountByMember(memberId); }
        catch (ApiException ex) {
            if (!"POINT_ACCOUNT_NOT_FOUND".equals(ex.getCode())) throw ex;
            return ensureAccount(memberId);
        }
    }

    /**
     * 落库一次积分余额变更：<b>CAS 成功之后</b>才追加流水与事实事件，返回版本已自增的账户。
     *
     * <p>顺序是刻意的（迁移时按钱包切片的同一决定调整）：先确认余额真的写进去了，再记流水。
     * 迁移前是「先 appendLedger 再 updateAccountOptimistic」，只靠外层 {@code @Transactional}
     * 在 CAS 抛错时把流水滚掉；一旦有人把这段挪出事务，账本就会记着「积分动过」而余额没动，
     * 而<b>对账只能相信账本</b>。现在 CAS 失败根本不会写出流水，不依赖事务回滚兜底。
     *
     * <p>CAS 失败（受影响 0 行）一律回 409 {@code POINT_ACCOUNT_VERSION_CONFLICT}：
     * 本轮余额已被他人改动，调用方重读重试；<b>不降级为无条件写入</b>。
     *
     * @param account         已读出的账户（提供 id / tenantId / customerId 与期望版本）
     * @param balanceAfter    本次写入的候选余额，必须已由调用方在同一临界区内判定过
     * @param points          账本记录的变动积分（正数增加、负数冲减）
     */
    private PointAccount applyBalance(PointAccount account, long balanceAfter, Long storeId, String entryType,
                                      long points, long ledgerBalanceAfter, String businessType, Long businessId,
                                      String idempotencyKey) {
        return applyBalance(account, balanceAfter, storeId, entryType, points, ledgerBalanceAfter, businessType,
                businessId, idempotencyKey, null, 0);
    }

    private PointAccount applyBalance(PointAccount account, long balanceAfter, Long storeId, String entryType,
                                      long points, long ledgerBalanceAfter, String businessType, Long businessId,
                                      String idempotencyKey, String ruleSnapshot, int expiryDays) {
        int expectedVersion = account.currentVersion();
        PointAccount candidate = new PointAccount(account.id(), account.tenantId(), account.customerId(),
                account.programId(), balanceAfter, account.frozen(), expectedVersion);
        if (!repository.updateBalanceWithVersion(candidate, expectedVersion)) {
            throw new ApiException(409, "POINT_ACCOUNT_VERSION_CONFLICT", "积分账户已被并发修改，请重试");
        }
        // 落库后的版本：SQL 里是 version = version + 1，Java 侧据此回填，供响应与后续写入使用。
        PointAccount saved = candidate.withVersion(expectedVersion + 1);
        if (saved.persisted()) {
            repository.appendLedger(new PointLedgerEntry(null, saved.tenantId(), storeId, saved.id(), entryType,
                    points,
                    // 币种快照（16_CURRENCY_CONVENTIONS §5「积分调整」）：积分本身非货币，
                    // 但该笔调整/抵扣的账面口径属于当时租户币种，落库固化以便报表与审计按币种归集。
                    CurrencyResolver.currentCode(),
                    ledgerBalanceAfter, businessType, businessId, idempotencyKey, ruleSnapshot,
                    expiryDays > 0 ? LocalDateTime.now().plusDays(expiryDays) : null,
                    LocalDateTime.now(), LocalDateTime.now()));
        }
        appendFact(entryType, saved, storeId, points, ledgerBalanceAfter);
        return saved;
    }

    private void appendFact(String entryType, PointAccount account, Long storeId, long delta, long balanceAfter) {
        if (!account.persisted()) {
            return;
        }
        eventOutbox.append(new CustomerFactEvent("customer.points.changed", "point_account",
                String.valueOf(account.id()), "{\"entryType\":\"" + entryType + "\",\"customerId\":"
                        + account.customerId() + ",\"storeId\":" + storeId + ",\"delta\":" + delta
                        + ",\"balanceAfter\":" + balanceAfter + "}"));
    }

    private void assertIdempotent(String idempotencyKey) {
        // 幂等占位：唯一键 uk_cst_point_ledger_idem(tenant_id, idempotency_key) 由 Flyway 兜底。
        if (repository.ledgerExists(idempotencyKey)) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键已存在");
        }
    }

    /**
     * 积分视图的数据载体：领域账户 + 端口返回的一页流水 + 请求分页参数。
     *
     * <p>刻意<b>不是</b>响应类型：响应视图（{@code api.dto.MemberPointsView}）由控制器装配，
     * 因此领域条目不可能被直接序列化出去，应用层也不依赖 MyBatis-Plus 的 {@code Page}。
     */
    public record MemberPointsData(PointAccount account, LedgerPage ledger, long page, long pageSize) {
    }
}
