package io.openware.platform.customer.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.audit.AuditErrorCodes;
import io.openware.infrastructure.currency.Currency;
import io.openware.infrastructure.currency.CurrencyResolver;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenChat币储值账本应用服务（账本只追加，扣减前校验余额）。
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
 *
 * <p><b>余额写入一律走乐观锁 CAS（{@link #persist}）</b>：本表没有其他并发保护手段，而
 * "读余额 → 判定够不够 → 写回"在并发下会各自覆盖对方——两个扣减都读到同一余额、都判定够扣、
 * 各自写回自己的结果，于是一笔钱被花两次而账本只记一次成功。
 */
@Service
public class WalletApplicationService {

    /** CAS 冲突后的重试次数；用尽仍冲突则拒绝，不降级为无条件写入。 */
    private static final int CAS_MAX_ATTEMPTS = 3;

    private final WalletAccountRepository repository;
    private final AuditClient auditClient;
    private final CustomerEventOutbox eventOutbox;

    /** 默认商户主体（tnt_legal_entity.id）；tnt_legal_entity 表与租户解析端点落地前用配置兜底。 */
    @Value("${app.wallet.default-legal-entity-id:1}")
    private Long defaultLegalEntityId;

    public WalletApplicationService(WalletAccountRepository repository, AuditClient auditClient) {
        this(repository, auditClient, CustomerEventOutbox.disabled());
    }

    @Autowired
    public WalletApplicationService(WalletAccountRepository repository, AuditClient auditClient,
                                    CustomerEventOutbox eventOutbox) {
        this.repository = repository;
        this.auditClient = auditClient;
        this.eventOutbox = eventOutbox;
    }

    /** 充值（现金/线下转账），追加 RECHARGE 流水，幂等。 */
    @Transactional
    public WalletAccount recharge(Long customerId, Long amount, String currency,
                                  String paymentMethod, String referenceNo, String idempotencyKey) {
        return recharge(null, customerId, amount, currency, paymentMethod, referenceNo, idempotencyKey);
    }

    /** 门店归因写路径：storeId 由入站适配器从已验签 TenantContext 提取。 */
    @Transactional
    public WalletAccount recharge(Long storeId, Long customerId, Long amount, String currency,
                                  String paymentMethod, String referenceNo, String idempotencyKey) {
        try {
            validateAmount(amount);
            if (currency == null || currency.isBlank()) {
                throw new ApiException(400, "CURRENCY_REQUIRED", "缺少币种");
            }
            assertIdempotent(idempotencyKey);
            String normalizedCurrency = Currency.parse(currency).code();
            WalletAccount account = repository.findByCustomerAndCurrency(customerId, normalizedCurrency)
                    .orElseGet(() -> repository.insert(
                            WalletAccount.open(customerId, defaultLegalEntityId, normalizedCurrency)));
            WalletAccount credited = account.credit(amount);
            WalletAccount saved = persist(credited, WalletLedgerEntry.TYPE_RECHARGE, storeId, amount,
                    null, idempotencyKey);
            // 高风险写操作（储值充值）审计：异步占位；tenantId 真实实现应从 TenantContext 解析。
            auditClient.recordAsync(AuditClient.AuditRecord.of(
                    null, null, "wallet.recharge", "wallet_account",
                    String.valueOf(saved.id()), null, idempotencyKey,
                    "{\"customerId\":" + customerId + ",\"amount\":" + amount + ",\"currency\":\""
                            + normalizedCurrency + "\"}"));
            return saved;
        } catch (RuntimeException failure) {
            recordFailure("wallet.recharge", "cst_wallet_account", customerId, failure);
            throw failure;
        }
    }

    /** 储值退还（余额扣减，账本 REFUND，审批占位）。没有账户 = 余额 0，按余额不足拒绝（不建账户）。 */
    @Transactional
    public WalletAccount refund(Long customerId, Long amount, String reason, String idempotencyKey) {
        return refund(null, customerId, amount, reason, idempotencyKey);
    }

    @Transactional
    public WalletAccount refund(Long storeId, Long customerId, Long amount, String reason, String idempotencyKey) {
        try {
            validateAmount(amount);
            assertIdempotent(idempotencyKey);
            WalletAccount account = requireAccountWithBalance(customerId, amount);
            return persist(account.deduct(amount), WalletLedgerEntry.TYPE_REFUND, storeId, amount, null,
                    idempotencyKey);
        } catch (RuntimeException failure) {
            recordFailure("wallet.refund", "cst_wallet_account", customerId, failure);
            throw failure;
        }
    }

    /**
     * 储值消费（组合收款 OpenChat币抵扣，账本 CONSUME，幂等）。
     * 金额最小货币单位整数；同主体同币种；扣减前校验余额，不足拒绝（LEDGER_INSUFFICIENT）。
     * 没有账户的会员按余额 0 处理：同样回 LEDGER_INSUFFICIENT（**不是** 404，也不为失败路径建账户）。
     */
    @Transactional
    public WalletAccount consume(Long customerId, Long amount, String currency, Long orderId,
                                 String idempotencyKey) {
        return consume(null, customerId, amount, currency, orderId, idempotencyKey);
    }

    @Transactional
    public WalletAccount consume(Long storeId, Long customerId, Long amount, String currency, Long orderId,
                                 String idempotencyKey) {
        try {
            validateAmount(amount);
            assertIdempotent(idempotencyKey);
            WalletAccount account = repository.findByCustomer(customerId)
                    // 没有储值账户 = 可用余额 0：先按余额不足拒绝（懒初始化下「没有账户」不是异常态）。
                    .orElseThrow(() -> new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足"));
            // 跨币种储值消费**默认关闭**（16_CURRENCY_CONVENTIONS §2.1「钱包账户币种已做跨币种拒绝」/
            // KTV_BUSINESS_01 §「CURRENCY_CORRIDOR_DISABLED 跨币种禁止」）：请求币种与账户币种不一致即拒绝，
            // 不做任何汇率换算、也不静默按账户币种消费。
            if (currency != null && !currency.isBlank()
                    && account.differsFromNormalizedCurrency(Currency.parse(currency).code())) {
                throw new ApiException(422, "CURRENCY_CORRIDOR_DISABLED", "禁止跨币种储值消费");
            }
            if (!account.canCover(amount)) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足");
            }
            // 冻结-扣减合一占位：真实实现先 HOLD（available→frozen）→ 结算成功 CONSUME（frozen 扣除）→ 失败 RELEASE（frozen→available）。
            return persist(account.deduct(amount), WalletLedgerEntry.TYPE_CONSUME, storeId, amount, orderId,
                    idempotencyKey);
        } catch (RuntimeException failure) {
            recordFailure("wallet.consume", "cst_wallet_account", customerId, failure);
            throw failure;
        }
    }

    /** 储值释放（组合收款失败补偿，账本 RELEASE，幂等）：归还已扣减的储值。真实 HOLD 语义下为 frozen→available。 */
    @Transactional
    public WalletAccount release(Long customerId, Long amount, String currency, Long orderId,
                                 String idempotencyKey) {
        return release(null, customerId, amount, currency, orderId, idempotencyKey);
    }

    @Transactional
    public WalletAccount release(Long storeId, Long customerId, Long amount, String currency, Long orderId,
                                 String idempotencyKey) {
        validateAmount(amount);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        WalletAccount account = repository.findByCustomer(customerId).orElse(null);
        if (account == null) {
            // 没有账户 = 此前没有任何储值扣减可归还：补偿天然幂等，直接回零额视图，不建账户、不改库。
            return zeroAccount(customerId);
        }
        if (repository.ledgerExists(idempotencyKey)) {
            return account;
        }
        return persist(account.credit(amount), WalletLedgerEntry.TYPE_RELEASE, storeId, amount, orderId,
                idempotencyKey);
    }

    /**
     * 储值余额（同主体同币种，租户级、跨门店共用）。
     *
     * <p><b>懒初始化读路径</b>：没有账户的会员<b>不会</b>被创建账户，而是返回零额只读视图
     * （{@code id == null}、可用/冻结均为 0、币种取当时租户币种），因此读接口永不 404。
     */
    public WalletAccount balance(Long memberId) {
        return repository.findByCustomer(memberId).orElseGet(() -> zeroAccount(memberId));
    }

    /** 储值账本分页（C 端/后台明细）。没有账户 = 无流水，返回空页，不创建账户。 */
    public LedgerPage ledger(Long memberId, long page, long pageSize) {
        return ledger(memberId, null, page, pageSize);
    }

    /** 流水读范围：总部不传门店看租户全量，门店上下文只看本店发生的流水。 */
    public LedgerPage ledger(Long memberId, Long storeId, long page, long pageSize) {
        WalletAccount account = repository.findByCustomer(memberId).orElse(null);
        if (account == null) {
            return new LedgerPage(List.of(), 0L);
        }
        long safePage = page < 1 ? 1 : page;
        long safeSize = pageSize < 1 ? 1 : pageSize;
        return repository.listLedger(account.id(), storeId, (safePage - 1) * safeSize, safeSize);
    }

    /**
     * 批量储值余额（储值管理列表用）：**一次** IN 查询取回整页会员的账户，
     * 避免「按会员逐个查余额」的 N+1，也避免没有账户的会员触发任何异常。
     * 返回的 Map <b>只含已开立账户</b>的会员；调用方按「缺失 = 余额 0、未开立」处理。
     */
    public Map<Long, WalletAccount> balances(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, WalletAccount> found = repository.findByCustomers(memberIds);
        Map<Long, WalletAccount> result = new LinkedHashMap<>();
        for (Long memberId : memberIds) {
            // 同会员多币种账户时以第一条为准：列表只展示「主余额」，逐会员明细由 /{id}/wallet 提供。
            WalletAccount account = found.get(memberId);
            if (account != null) {
                result.putIfAbsent(memberId, account);
            }
        }
        return result;
    }

    /**
     * 落库一次余额变更：CAS 成功后再写流水与事实事件。
     *
     * <p>顺序是刻意的——先确认余额真的写进去了，再记流水。反过来（先写流水再改余额）在 CAS 失败时
     * 会留下一条"钱动过"的流水而余额没动，对账只能相信流水。
     *
     * <p>CAS 冲突时重新读账户重算；重试用尽仍冲突则拒绝（{@code WALLET_CONCURRENT_MODIFICATION}），
     * **不降级为无条件写入**——那正是本方法要修掉的行为。
     *
     * <p>重试依赖事务语义：本方法在写事务内被调用，失败那一轮的流水与事件必须随事务一起作废，
     * 下一轮才是从干净状态重算的。重试用尽则明确拒绝——宁可让调用方重试，也不接受一次
     * "看起来成功"的覆盖。
     */
    private WalletAccount persist(WalletAccount candidate, String entryType, Long storeId, long amount,
                                  Long orderId, String idempotencyKey) {
        for (int attempt = 0; attempt < CAS_MAX_ATTEMPTS; attempt++) {
            if (repository.updateBalanceWithVersion(candidate, candidate.currentVersion())) {
                long balanceAfter = candidate.available();
                repository.insertLedger(new WalletLedgerEntry(null, candidate.tenantId(), storeId,
                        candidate.id(), entryType, amount, balanceAfter,
                        // 币种快照（§5）：账本自证币种，不再依赖回查账户；取账户币种保证
                        // 「账户 ↔ 流水」永不出现两种币种。
                        Currency.parse(candidate.currencyCode()).code(), candidate.tokenAmount(),
                        candidate.tokenBrandName(), orderId, null, idempotencyKey, LocalDateTime.now()));
                appendFact(entryType, candidate, storeId,
                        WalletLedgerEntry.TYPE_CONSUME.equals(entryType)
                                || WalletLedgerEntry.TYPE_REFUND.equals(entryType) ? -amount : amount,
                        balanceAfter);
                return withVersion(candidate, candidate.currentVersion() + 1);
            }
            // 冲突：本轮余额已被他人改动，重新读账户与版本再算一次。
            candidate = repository.findById(candidate.id())
                    .map(fresh -> applyDelta(fresh, entryType, amount))
                    .orElseThrow(() -> new ApiException(409, "WALLET_CONCURRENT_MODIFICATION",
                            "储值账户并发更新，请重试"));
        }
        throw new ApiException(409, "WALLET_CONCURRENT_MODIFICATION", "储值账户并发更新，请重试");
    }

    /** 在最新余额上重新应用本次变动；余额不足时抛出（重试期间余额可能已被他人扣走）。 */
    private WalletAccount applyDelta(WalletAccount fresh, String entryType, long amount) {
        if (WalletLedgerEntry.TYPE_CONSUME.equals(entryType) || WalletLedgerEntry.TYPE_REFUND.equals(entryType)) {
            if (!fresh.canCover(amount)) {
                throw new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足");
            }
            return fresh.deduct(amount);
        }
        return fresh.credit(amount);
    }

    private static WalletAccount withVersion(WalletAccount account, int version) {
        return new WalletAccount(account.id(), account.tenantId(), account.customerId(),
                account.legalEntityId(), account.currencyCode(), account.available(), account.frozen(),
                account.tokenAmount(), account.tokenBrandName(), account.accountOpened(), account.status(),
                version);
    }

    /** 取账户并确认余额足够；没有账户或余额不足一律 {@code LEDGER_INSUFFICIENT}（不是 404）。 */
    private WalletAccount requireAccountWithBalance(Long customerId, Long amount) {
        WalletAccount account = repository.findByCustomer(customerId).orElse(null);
        if (account == null || !account.canCover(amount)) {
            throw new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足");
        }
        return account;
    }

    /**
     * 零额只读视图：**没有账户**时的余额口径。不是数据库行（{@code id == null}），
     * 因此不会被 update/审计当成真实账户；币种口径与真实账户创建时一致（当时租户币种）。
     */
    private WalletAccount zeroAccount(Long customerId) {
        return WalletAccount.zeroFor(customerId, defaultLegalEntityId, CurrencyResolver.currentCode());
    }

    private void appendFact(String entryType, WalletAccount account, Long storeId, long delta, long balanceAfter) {
        if (!account.persisted()) {
            return;
        }
        eventOutbox.append(new CustomerFactEvent("customer.wallet.changed", "wallet_account",
                String.valueOf(account.id()), "{\"entryType\":\"" + entryType + "\",\"customerId\":"
                        + account.customerId() + ",\"storeId\":" + storeId + ",\"delta\":" + delta
                        + ",\"balanceAfter\":" + balanceAfter + "}"));
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
        // 这里的预检只覆盖"先到者已提交"的常见情形；真正防重仍靠唯一键（并发同键时后到者撞键回滚）。
        if (repository.ledgerExists(idempotencyKey)) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "幂等键已存在");
        }
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
