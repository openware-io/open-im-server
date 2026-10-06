package io.openware.platform.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 积分账户应用服务（流水只追加，扣减前校验余额）。
 * 积分调整：entry_type = ADJUST，points 可正可负，命令幂等 commandId。
 */
@Service
public class PointApplicationService {
    private final PointAccountMapper accountMapper;
    private final PointLedgerMapper ledgerMapper;
    private final AuditClient auditClient;
    private final CustomerEventOutbox eventOutbox;
    private final PointRuleConfigApplicationService ruleService;

    public PointApplicationService(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper,
                                   AuditClient auditClient) {
        this(accountMapper, ledgerMapper, auditClient, CustomerEventOutbox.disabled(), null);
    }

    @Autowired
    public PointApplicationService(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper,
                                   AuditClient auditClient, CustomerEventOutbox eventOutbox,
                                   PointRuleConfigApplicationService ruleService) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.auditClient = auditClient;
        this.eventOutbox = eventOutbox;
        this.ruleService = ruleService;
    }

    public PointApplicationService(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper,
                                   AuditClient auditClient, CustomerEventOutbox eventOutbox) {
        this(accountMapper, ledgerMapper, auditClient, eventOutbox, null);
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public PointApplicationService(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper) {
        this(accountMapper, ledgerMapper, AuditClient.disabled(), CustomerEventOutbox.disabled(), null);
    }

    /** 会员积分视图：积分账户 + 账本分页（均为「个数」数量口径，响应不含币种与代币字段）。 */
    public MemberPointsView view(Long memberId, long page, long pageSize) {
        return view(memberId, null, page, pageSize);
    }

    /** 流水读范围：总部不传门店看租户全量，门店上下文只看本店发生的流水。 */
    public MemberPointsView view(Long memberId, Long storeId, long page, long pageSize) {
        CstPointAccountPo account = requireAccountByMember(memberId);
        LambdaQueryWrapper<CstPointLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstPointLedgerPo::getAccountId, account.getId())
                .eq(storeId != null, CstPointLedgerPo::getStoreId, storeId)
                .orderByDesc(CstPointLedgerPo::getId);
        Page<CstPointLedgerPo> ledger = ledgerMapper.selectPage(new Page<>(page, pageSize), qw);
        return new MemberPointsView(account, toRows(ledger));
    }

    /**
     * 积分流水按「数量口径」投影：只暴露积分个数及其余额，剥离币种快照与内部字段。
     * 分页元信息（current/size/total）原样保留，前端分页结构不变。
     */
    private static Page<PointLedgerRow> toRows(Page<CstPointLedgerPo> ledger) {
        Page<PointLedgerRow> rows = new Page<>(ledger.getCurrent(), ledger.getSize(), ledger.getTotal());
        rows.setRecords(ledger.getRecords().stream().map(PointLedgerRow::of).toList());
        return rows;
    }

    /** 积分调整（ADJUST，命令幂等 commandId），高风险需审计。 */
    @Transactional
    public CstPointAccountPo adjust(Long memberId, Long points, String reason, String commandId) {
        return adjust(null, memberId, points, reason, commandId);
    }

    @Transactional
    public CstPointAccountPo adjust(Long storeId, Long memberId, Long points, String reason, String commandId) {
        try {
            if (points == null || points == 0) {
                throw new ApiException(400, "POINTS_INVALID", "调整积分不能为0");
            }
            if (commandId == null || commandId.isBlank()) {
                throw new ApiException(400, "COMMAND_ID_REQUIRED", "缺少 commandId");
            }
            CstPointAccountPo account = requireOrCreateAccount(memberId);
            assertIdempotent(commandId);
            if (points < 0 && account.getAvailablePoints() < -points) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "积分余额不足");
            }
            long balanceAfter = account.getAvailablePoints() + points;
            appendLedger(storeId, account, "ADJUST", points, balanceAfter, null, null, commandId);
            appendFact("ADJUST", account, storeId, points, balanceAfter);
            account.setAvailablePoints(balanceAfter);
            bump(account);
            updateAccountOptimistic(account, balanceAfter);
            // 积分属于会员资产：人工调整必须留痕（原因 + 变更前后余额），命令号即幂等键。
            auditClient.recordAsync(AuditClient.AuditRecord.builder()
                    .action("points.adjust")
                    .resourceType("cst_point_account").resourceId(String.valueOf(account.getId()))
                    .operatorName(null)
                    .idempotencyKey(commandId)
                    .detailJson("{\"memberId\":" + memberId + ",\"points\":" + points + ",\"balanceAfter\":"
                            + balanceAfter + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "\\\""))
                            + "\"}")
                    .build());
            return account;
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
    public CstPointAccountPo redeem(Long memberId, Long points, Long orderId, String idempotencyKey) {
        return redeem(null, memberId, points, orderId, idempotencyKey);
    }

    @Transactional
    public CstPointAccountPo redeem(Long storeId, Long memberId, Long points, Long orderId, String idempotencyKey) {
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
        CstPointAccountPo account = requireAccountByMember(memberId);
        if (account.getAvailablePoints() < points) {
            throw new ApiException(422, "LEDGER_INSUFFICIENT", "积分余额不足");
        }
        long balanceAfter = account.getAvailablePoints() - points;
        appendLedger(storeId, account, "REDEEM", -points, balanceAfter, "ORDER", orderId, idempotencyKey);
        appendFact("REDEEM", account, storeId, -points, balanceAfter);
        account.setAvailablePoints(balanceAfter);
        bump(account);
        updateAccountOptimistic(account, balanceAfter);
        return account;
    }

    /** 积分释放（组合收款失败补偿，账本 REVERSE 反向流水，幂等）：归还已抵扣的积分。真实 HOLD 语义下为 frozen→available。 */
    @Transactional
    public CstPointAccountPo release(Long memberId, Long points, Long orderId, String idempotencyKey) {
        return release(null, memberId, points, orderId, idempotencyKey);
    }

    @Transactional
    public CstPointAccountPo release(Long storeId, Long memberId, Long points, Long orderId, String idempotencyKey) {
        if (points == null || points <= 0) {
            throw new ApiException(400, "POINTS_INVALID", "释放积分必须为正整数");
        }
        if (ledgerExists(idempotencyKey)) {
            return requireAccountByMember(memberId);
        }
        CstPointAccountPo account = requireAccountByMember(memberId);
        long balanceAfter = account.getAvailablePoints() + points;
        appendLedger(storeId, account, "REVERSE", points, balanceAfter, "ORDER", orderId, idempotencyKey);
        appendFact("REVERSE", account, storeId, points, balanceAfter);
        account.setAvailablePoints(balanceAfter);
        bump(account);
        updateAccountOptimistic(account, balanceAfter);
        return account;
    }

    /** 积分余额（组合收款抵扣前校验）。 */
    public CstPointAccountPo balance(Long memberId) {
        return requireAccountByMember(memberId);
    }

    /** 确保会员积分账户存在（C 端懒创建），默认积分计划 1。 */
    public CstPointAccountPo ensureAccount(Long customerId) {
        if (customerId == null) {
            throw new ApiException(400, "CUSTOMER_ID_REQUIRED", "缺少 customerId");
        }
        CstPointAccountPo existing = accountMapper.selectOne(new LambdaQueryWrapper<CstPointAccountPo>()
                .eq(CstPointAccountPo::getCustomerId, customerId).last("LIMIT 1"));
        if (existing != null) return existing;
        CstPointAccountPo po = new CstPointAccountPo();
        po.setCustomerId(customerId);
        po.setProgramId(1L);
        po.setAvailablePoints(0L);
        po.setFrozenPoints(0L);
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(po);
        return po;
    }

    private CstPointAccountPo requireAccountByMember(Long memberId) {
        LambdaQueryWrapper<CstPointAccountPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstPointAccountPo::getCustomerId, memberId).last("LIMIT 1");
        CstPointAccountPo po = accountMapper.selectOne(qw);
        if (po == null) throw new ApiException(404, "POINT_ACCOUNT_NOT_FOUND", "积分账户不存在");
        return po;
    }

    /** 支付确认后的积分获得入口；倍率、规则快照和流水失效时间均由 Customer 域决定。 */
    @Transactional
    public CstPointAccountPo earn(Long storeId, Long memberId, long eligibleAmountMinor,
                                  Long orderId, String idempotencyKey) {
        if (eligibleAmountMinor <= 0) throw new ApiException(400, "EARN_AMOUNT_INVALID", "可积分金额必须为正数");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ApiException(400, "COMMAND_ID_REQUIRED", "缺少积分获得幂等键");
        if (ledgerExists(idempotencyKey)) return requireOrCreateAccount(memberId);
        TenantContext context = TenantContextHolder.get();
        long tenantId = context == null ? 0L : context.tenantId();
        PointRuleConfigApplicationService.PointRule rule = ruleService == null
                ? PointRuleConfigApplicationService.PointRule.DEFAULT
                : ruleService.resolve(tenantId, storeId, context == null ? null : context.businessType());
        long points = BigDecimal.valueOf(eligibleAmountMinor).multiply(rule.earnRate())
                .setScale(0, RoundingMode.DOWN).longValueExact();
        CstPointAccountPo account = requireOrCreateAccount(memberId);
        if (points <= 0) return account;
        long balanceAfter = account.getAvailablePoints() + points;
        String snapshot = "{\"earnRate\":" + rule.earnRate() + ",\"expiryDays\":" + rule.expiryDays()
                + ",\"businessType\":\"" + rule.businessType() + "\"}";
        appendLedger(storeId, account, "EARN", points, balanceAfter, "ORDER", orderId, idempotencyKey,
                snapshot, rule.expiryDays());
        appendFact("EARN", account, storeId, points, balanceAfter);
        account.setAvailablePoints(balanceAfter);
        bump(account);
        updateAccountOptimistic(account, balanceAfter);
        return account;
    }

    /** 收款确认事实消费的积分获得命令；payload 由消息消费者解析后调用。 */
    public CstPointAccountPo earnFromConfirmedCollection(Long storeId, Long memberId, long collectedAmount,
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

    private CstPointAccountPo requireOrCreateAccount(Long memberId) {
        try { return requireAccountByMember(memberId); }
        catch (ApiException ex) {
            if (!"POINT_ACCOUNT_NOT_FOUND".equals(ex.getCode())) throw ex;
            return ensureAccount(memberId);
        }
    }

    private void appendLedger(Long storeId, CstPointAccountPo account, String entryType, long points, long balanceAfter,
                              String businessType, Long businessId, String idempotencyKey) {
        appendLedger(storeId, account, entryType, points, balanceAfter, businessType, businessId, idempotencyKey, null, 0);
    }

    private void appendLedger(Long storeId, CstPointAccountPo account, String entryType, long points, long balanceAfter,
                              String businessType, Long businessId, String idempotencyKey, String ruleSnapshot,
                              int expiryDays) {
        CstPointLedgerPo ledger = new CstPointLedgerPo();
        ledger.setStoreId(storeId);
        ledger.setAccountId(account.getId());
        ledger.setEntryType(entryType);
        ledger.setPoints(points);
        // 币种快照（16_CURRENCY_CONVENTIONS §5「积分调整」）：积分本身非货币，
        // 但该笔调整/抵扣的账面口径属于当时租户币种，落库固化以便报表与审计按币种归集。
        ledger.setCurrencyCode(CurrencyResolver.currentCode());
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBusinessType(businessType);
        ledger.setBusinessId(businessId);
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setRuleSnapshotJson(ruleSnapshot);
        ledger.setExpiresAt(expiryDays > 0 ? LocalDateTime.now().plusDays(expiryDays) : null);
        ledger.setOccurredAt(LocalDateTime.now());
        ledger.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(ledger);
    }

    private void appendFact(String entryType, CstPointAccountPo account, Long storeId, long delta, long balanceAfter) {
        eventOutbox.append(new CustomerFactEvent("customer.points.changed", "point_account",
                String.valueOf(account.getId()), "{\"entryType\":\"" + entryType + "\",\"customerId\":"
                        + account.getCustomerId() + ",\"storeId\":" + storeId + ",\"delta\":" + delta
                        + ",\"balanceAfter\":" + balanceAfter + "}"));
    }

    private void assertIdempotent(String idempotencyKey) {
        // 幂等占位：唯一键 uk_cst_point_ledger_idem(tenant_id, idempotency_key) 由 Flyway 兜底。
        LambdaQueryWrapper<CstPointLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstPointLedgerPo::getIdempotencyKey, idempotencyKey);
        if (ledgerMapper.selectCount(qw) > 0) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键已存在");
        }
    }

    private boolean ledgerExists(String idempotencyKey) {
        LambdaQueryWrapper<CstPointLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstPointLedgerPo::getIdempotencyKey, idempotencyKey);
        return ledgerMapper.selectCount(qw) > 0;
    }

    private void bump(CstPointAccountPo account) {
        account.setVersion(account.getVersion() == null ? 1 : account.getVersion() + 1);
        account.setUpdatedAt(LocalDateTime.now());
    }

    private void updateAccountOptimistic(CstPointAccountPo account, long balanceAfter) {
        int newVersion = account.getVersion() == null ? 1 : account.getVersion();
        int oldVersion = Math.max(0, newVersion - 1);
        int updated = accountMapper.update(null, new UpdateWrapper<CstPointAccountPo>()
                .eq("id", account.getId())
                .eq("version", oldVersion)
                .set("available_points", balanceAfter)
                .set("version", newVersion)
                .set("updated_at", account.getUpdatedAt()));
        if (updated == 0) throw new ApiException(409, "POINT_ACCOUNT_VERSION_CONFLICT", "积分账户已被并发修改，请重试");
    }
}
