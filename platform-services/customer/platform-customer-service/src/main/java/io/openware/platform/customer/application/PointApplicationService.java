package io.openware.platform.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    public PointApplicationService(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper,
                                   AuditClient auditClient) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.auditClient = auditClient;
    }

    /** 兼容既有装配：不传审计客户端时使用关闭态（生产装配始终注入真实客户端）。 */
    public PointApplicationService(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper) {
        this(accountMapper, ledgerMapper, AuditClient.disabled());
    }

    /** 会员积分视图：积分账户 + 账本分页（均为「个数」数量口径，响应不含币种与代币字段）。 */
    public MemberPointsView view(Long memberId, long page, long pageSize) {
        CstPointAccountPo account = requireAccountByMember(memberId);
        LambdaQueryWrapper<CstPointLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstPointLedgerPo::getAccountId, account.getId()).orderByDesc(CstPointLedgerPo::getId);
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
        try {
            if (points == null || points == 0) {
                throw new ApiException(400, "POINTS_INVALID", "调整积分不能为0");
            }
            if (commandId == null || commandId.isBlank()) {
                throw new ApiException(400, "COMMAND_ID_REQUIRED", "缺少 commandId");
            }
            CstPointAccountPo account = requireAccountByMember(memberId);
            assertIdempotent(commandId);
            if (points < 0 && account.getAvailablePoints() < -points) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "积分余额不足");
            }
            long balanceAfter = account.getAvailablePoints() + points;
            appendLedger(account, "ADJUST", points, balanceAfter, null, null, commandId);
            account.setAvailablePoints(balanceAfter);
            bump(account);
            accountMapper.updateById(account);
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
        if (points == null || points <= 0) {
            throw new ApiException(400, "POINTS_INVALID", "抵扣积分必须为正整数");
        }
        assertIdempotent(idempotencyKey);
        CstPointAccountPo account = requireAccountByMember(memberId);
        if (account.getAvailablePoints() < points) {
            throw new ApiException(422, "LEDGER_INSUFFICIENT", "积分余额不足");
        }
        long balanceAfter = account.getAvailablePoints() - points;
        appendLedger(account, "REDEEM", -points, balanceAfter, "ORDER", orderId, idempotencyKey);
        account.setAvailablePoints(balanceAfter);
        bump(account);
        accountMapper.updateById(account);
        return account;
    }

    /** 积分释放（组合收款失败补偿，账本 REVERSE 反向流水，幂等）：归还已抵扣的积分。真实 HOLD 语义下为 frozen→available。 */
    @Transactional
    public CstPointAccountPo release(Long memberId, Long points, Long orderId, String idempotencyKey) {
        if (points == null || points <= 0) {
            throw new ApiException(400, "POINTS_INVALID", "释放积分必须为正整数");
        }
        if (ledgerExists(idempotencyKey)) {
            return requireAccountByMember(memberId);
        }
        CstPointAccountPo account = requireAccountByMember(memberId);
        long balanceAfter = account.getAvailablePoints() + points;
        appendLedger(account, "REVERSE", points, balanceAfter, "ORDER", orderId, idempotencyKey);
        account.setAvailablePoints(balanceAfter);
        bump(account);
        accountMapper.updateById(account);
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

    private void appendLedger(CstPointAccountPo account, String entryType, long points, long balanceAfter,
                              String businessType, Long businessId, String idempotencyKey) {
        CstPointLedgerPo ledger = new CstPointLedgerPo();
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
        ledger.setOccurredAt(LocalDateTime.now());
        ledger.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(ledger);
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
}
