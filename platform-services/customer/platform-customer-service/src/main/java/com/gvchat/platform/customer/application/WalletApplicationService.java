package com.gvchat.platform.customer.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.audit.AuditErrorCodes;
import com.gvchat.infrastructure.currency.Currency;
import com.gvchat.infrastructure.currency.CurrencyResolver;
import com.gvchat.platform.customer.infra.persistence.mapper.WalletAccountMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.WalletLedgerMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletAccountPo;
import com.gvchat.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A380币储值账本应用服务（账本只追加，扣减前校验余额）。
 * 金额单位为最小货币单位整数。
 *
 * <p><b>账户懒初始化（2026-09-19）</b>：储值账户<b>只在首次充值</b>（{@link #recharge}）时创建。
 * 读路径（{@link #balance} / {@link #ledger}）对没有账户的会员返回「零额只读视图 / 空流水」，
 * <b>既不落库也不抛 404</b>——绝大多数会员没有储值，不该被批量建出空账户，也不该让
 * 「储值管理」列表逐个查余额时报 {@code WALLET_ACCOUNT_NOT_FOUND 储值账户不存在}。
 * 「没有账户」的语义就是余额 0：扣减类操作（消费/退还）按余额不足拒绝，
 * 补偿类归还（RELEASE）为无副作用空操作。
 *
 * <p>储值账户是<b>租户级</b>资产：按 {@code (tenant_id, customer_id, legal_entity_id, currency_code)}
 * 唯一，同一租户内<b>跨门店共用</b>，与门店上下文无关。
 */
@Service
public class WalletApplicationService {
    private final WalletAccountMapper accountMapper;
    private final WalletLedgerMapper ledgerMapper;
    private final AuditClient auditClient;

    /** 默认商户主体（tnt_legal_entity.id）；tnt_legal_entity 表与租户解析端点落地前用配置兜底。 */
    @Value("${app.wallet.default-legal-entity-id:1}")
    private Long defaultLegalEntityId;

    public WalletApplicationService(WalletAccountMapper accountMapper, WalletLedgerMapper ledgerMapper,
                                    AuditClient auditClient) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.auditClient = auditClient;
    }

    /** 充值（现金/线下转账），追加 RECHARGE 流水，幂等。 */
    @Transactional
    public CstWalletAccountPo recharge(Long customerId, Long amount, String currency,
                                       String paymentMethod, String referenceNo, String idempotencyKey) {
        try {
            validateAmount(amount);
            if (currency == null || currency.isBlank()) {
                throw new ApiException(400, "CURRENCY_REQUIRED", "缺少币种");
            }
            assertIdempotent(idempotencyKey);
            CstWalletAccountPo account = findOrCreate(customerId, Currency.parse(currency).code());
            // 线下转账需关联凭证 referenceNo，真实实现写 iam_audit_log + 财务/店长复核
            long balanceAfter = account.getAvailableAmount() + amount;
            appendLedger(account, "RECHARGE", amount, balanceAfter, null, idempotencyKey);
            account.setAvailableAmount(balanceAfter);
            bump(account);
            accountMapper.updateById(account);
            // 高风险写操作（储值充值）审计：异步占位；tenantId 真实实现应从 TenantContext 解析。
            auditClient.recordAsync(AuditClient.AuditRecord.of(
                    null, null, "wallet.recharge", "wallet_account",
                    String.valueOf(account.getId()), null, idempotencyKey,
                    "{\"customerId\":" + customerId + ",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}"));
            return account;
        } catch (RuntimeException failure) {
            recordFailure("wallet.recharge", "cst_wallet_account", customerId, failure);
            throw failure;
        }
    }

    /** 储值退还（余额扣减，账本 REFUND，审批占位）。没有账户 = 余额 0，按余额不足拒绝（不建账户）。 */
    @Transactional
    public CstWalletAccountPo refund(Long customerId, Long amount, String reason, String idempotencyKey) {
        try {
            validateAmount(amount);
            assertIdempotent(idempotencyKey);
            CstWalletAccountPo account = findByMember(customerId);
            if (account == null || account.getAvailableAmount() < amount) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足");
            }
            long balanceAfter = account.getAvailableAmount() - amount;
            appendLedger(account, "REFUND", amount, balanceAfter, null, idempotencyKey);
            account.setAvailableAmount(balanceAfter);
            bump(account);
            accountMapper.updateById(account);
            return account;
        } catch (RuntimeException failure) {
            recordFailure("wallet.refund", "cst_wallet_account", customerId, failure);
            throw failure;
        }
    }

    /**
     * 储值消费（组合收款 A380币抵扣，账本 CONSUME，幂等）。
     * 金额最小货币单位整数；同主体同币种；扣减前校验余额，不足拒绝（LEDGER_INSUFFICIENT）。
     * 没有账户的会员按余额 0 处理：同样回 LEDGER_INSUFFICIENT（**不是** 404，也不为失败路径建账户）。
     */
    @Transactional
    public CstWalletAccountPo consume(Long customerId, Long amount, String currency, Long orderId,
                                      String idempotencyKey) {
        try {
            validateAmount(amount);
            assertIdempotent(idempotencyKey);
            CstWalletAccountPo account = findByMember(customerId);
            // 没有储值账户 = 可用余额 0：先按余额不足拒绝（懒初始化下「没有账户」不是异常态）。
            if (account == null) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足");
            }
            // 跨币种储值消费**默认关闭**（16_CURRENCY_CONVENTIONS §2.1「钱包账户币种已做跨币种拒绝」/
            // KTV_BUSINESS_01 §「CURRENCY_CORRIDOR_DISABLED 跨币种禁止」）：请求币种与账户币种不一致即拒绝，
            // 不做任何汇率换算、也不静默按账户币种消费。
            if (currency != null && !currency.isBlank()
                    && !Currency.parse(currency).code().equals(Currency.parse(account.getCurrencyCode()).code())) {
                throw new ApiException(422, "CURRENCY_CORRIDOR_DISABLED", "禁止跨币种储值消费");
            }
            if (account.getAvailableAmount() < amount) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足");
            }
            // 冻结-扣减合一占位：真实实现先 HOLD（available→frozen）→ 结算成功 CONSUME（frozen 扣除）→ 失败 RELEASE（frozen→available）。
            long balanceAfter = account.getAvailableAmount() - amount;
            appendLedger(account, "CONSUME", amount, balanceAfter, orderId, idempotencyKey);
            account.setAvailableAmount(balanceAfter);
            bump(account);
            accountMapper.updateById(account);
            return account;
        } catch (RuntimeException failure) {
            recordFailure("wallet.consume", "cst_wallet_account", customerId, failure);
            throw failure;
        }
    }

    /** 储值释放（组合收款失败补偿，账本 RELEASE，幂等）：归还已扣减的储值。真实 HOLD 语义下为 frozen→available。 */
    @Transactional
    public CstWalletAccountPo release(Long customerId, Long amount, String currency, Long orderId,
                                      String idempotencyKey) {
        validateAmount(amount);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        CstWalletAccountPo account = findByMember(customerId);
        if (account == null) {
            // 没有账户 = 此前没有任何储值扣减可归还：补偿天然幂等，直接回零额视图，不建账户、不改库。
            return zeroAccount(customerId);
        }
        if (ledgerExists(idempotencyKey)) {
            return account;
        }
        long balanceAfter = account.getAvailableAmount() + amount;
        appendLedger(account, "RELEASE", amount, balanceAfter, orderId, idempotencyKey);
        account.setAvailableAmount(balanceAfter);
        bump(account);
        accountMapper.updateById(account);
        return account;
    }

    /**
     * 储值余额（同主体同币种，租户级、跨门店共用）。
     *
     * <p><b>懒初始化读路径</b>：没有账户的会员<b>不会</b>被创建账户，而是返回零额只读视图
     * （{@code id == null}、可用/冻结均为 0、币种取当时租户币种），因此读接口永不 404。
     */
    public CstWalletAccountPo balance(Long memberId) {
        CstWalletAccountPo account = findByMember(memberId);
        return account == null ? zeroAccount(memberId) : account;
    }

    /** 储值账本分页（C 端/后台明细）。没有账户 = 无流水，返回空页，不创建账户。 */
    public Page<CstWalletLedgerPo> ledger(Long memberId, long page, long pageSize) {
        CstWalletAccountPo account = findByMember(memberId);
        if (account == null) {
            return new Page<>(page, pageSize);
        }
        LambdaQueryWrapper<CstWalletLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstWalletLedgerPo::getWalletAccountId, account.getId()).orderByDesc(CstWalletLedgerPo::getId);
        return ledgerMapper.selectPage(new Page<>(page, pageSize), qw);
    }

    /**
     * 批量储值余额（储值管理列表用）：**一次** IN 查询取回整页会员的账户，
     * 避免「按会员逐个查余额」的 N+1，也避免没有账户的会员触发任何异常。
     * 返回的 Map <b>只含已开立账户</b>的会员；调用方按「缺失 = 余额 0、未开立」处理。
     */
    public Map<Long, CstWalletAccountPo> balances(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<CstWalletAccountPo> qw = new LambdaQueryWrapper<>();
        qw.in(CstWalletAccountPo::getCustomerId, memberIds);
        Map<Long, CstWalletAccountPo> result = new LinkedHashMap<>();
        for (CstWalletAccountPo po : accountMapper.selectList(qw)) {
            // 同会员多币种账户时以第一条为准：列表只展示「主余额」，逐会员明细由 /{id}/wallet 提供。
            result.putIfAbsent(po.getCustomerId(), po);
        }
        return result;
    }

    private CstWalletAccountPo findOrCreate(Long customerId, String currency) {
        LambdaQueryWrapper<CstWalletAccountPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstWalletAccountPo::getCustomerId, customerId)
          .eq(CstWalletAccountPo::getCurrencyCode, currency)
          .last("LIMIT 1");
        CstWalletAccountPo po = accountMapper.selectOne(qw);
        if (po != null) return po;
        po = new CstWalletAccountPo();
        po.setCustomerId(customerId);
        po.setLegalEntityId(defaultLegalEntityId);
        po.setCurrencyCode(currency);
        po.setAvailableAmount(0L);
        po.setFrozenAmount(0L);
        po.setStatus("ACTIVE");
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(po);
        return po;
    }

    /**
     * 零额只读视图：**没有账户**时的余额口径。不是数据库行（{@code id == null}），
     * 因此不会被 update/审计当成真实账户；币种口径与真实账户创建时一致（当时租户币种）。
     */
    private CstWalletAccountPo zeroAccount(Long customerId) {
        CstWalletAccountPo po = new CstWalletAccountPo();
        po.setCustomerId(customerId);
        po.setLegalEntityId(defaultLegalEntityId);
        po.setCurrencyCode(CurrencyResolver.currentCode());
        po.setAvailableAmount(0L);
        po.setFrozenAmount(0L);
        po.setVersion(0);
        return po;
    }

    /** 按会员取储值账户；**没有账户返回 null**（懒初始化：调用方决定是零额视图还是拒绝），不再抛 404。 */
    private CstWalletAccountPo findByMember(Long customerId) {
        LambdaQueryWrapper<CstWalletAccountPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstWalletAccountPo::getCustomerId, customerId).last("LIMIT 1");
        return accountMapper.selectOne(qw);
    }

    private void appendLedger(CstWalletAccountPo account, String entryType, long amount, long balanceAfter,
                              Long orderId, String idempotencyKey) {
        CstWalletLedgerPo ledger = new CstWalletLedgerPo();
        ledger.setWalletAccountId(account.getId());
        ledger.setEntryType(entryType);
        ledger.setAmount(amount);
        ledger.setBalanceAfter(balanceAfter);
        // 币种快照（§5）：账本自证币种，不再依赖回查账户；取账户币种保证「账户 ↔ 流水」永不出现两种币种。
        ledger.setCurrencyCode(Currency.parse(account.getCurrencyCode()).code());
        ledger.setOrderId(orderId);
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setOccurredAt(LocalDateTime.now());
        ledger.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(ledger);
    }

    private void validateAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new ApiException(400, "AMOUNT_INVALID", "金额必须为正整数(最小货币单位)");
        }
    }

    private void assertIdempotent(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        // 幂等占位：唯一键 uk_cst_wallet_ledger_idem(tenant_id, idempotency_key) 由 Flyway 兜底。
        LambdaQueryWrapper<CstWalletLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstWalletLedgerPo::getIdempotencyKey, idempotencyKey);
        if (ledgerMapper.selectCount(qw) > 0) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键已存在");
        }
    }

    private boolean ledgerExists(String idempotencyKey) {
        LambdaQueryWrapper<CstWalletLedgerPo> qw = new LambdaQueryWrapper<>();
        qw.eq(CstWalletLedgerPo::getIdempotencyKey, idempotencyKey);
        return ledgerMapper.selectCount(qw) > 0;
    }

    private void bump(CstWalletAccountPo account) {
        account.setVersion(account.getVersion() == null ? 1 : account.getVersion() + 1);
        account.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 领域内失败留痕：储值写操作的失败出口（参数非法/幂等冲突/余额不足/落库失败）必须落一条
     * {@code result=FAILURE} 记录 —— BFF 拦截器只兜得住 BFF 路径，领域服务内部的失败此前完全没有留痕。
     *
     * <p>约束：审计只走 {@link AuditClient#recordAsync}（失败仅 WARN），业务异常原样抛出，
     * 留痕绝不改变业务结果；detail 只放检索用的非敏感字段（会员 ID），金额与支付凭证不入详情。
     */
    private void recordFailure(String action, String resourceType, Long customerId, RuntimeException failure) {
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(customerId == null ? null : String.valueOf(customerId))
                .resourceName(customerId == null ? null : "customer:" + customerId)
                .result(AuditClient.AuditRecord.RESULT_FAILURE)
                .errorCode(AuditErrorCodes.of(failure))
                .detailJson("{\"customerId\":" + customerId + "}")
                .build());
    }
}
