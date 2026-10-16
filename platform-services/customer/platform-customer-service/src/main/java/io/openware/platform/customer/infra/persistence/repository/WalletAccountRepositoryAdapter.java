package io.openware.platform.customer.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import io.openware.platform.customer.infra.persistence.mapper.WalletAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.WalletLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link WalletAccountRepository} 的持久化适配器。
 *
 * <p>核心是 {@link #updateBalanceWithVersion}：余额写入必须带版本条件，见该方法的说明。
 * 分页用显式 LIMIT 而不引入 MyBatis-Plus 的 {@code Page}/{@code IPage}，以免把分页实现类型泄漏到端口。
 */
@Repository
public class WalletAccountRepositoryAdapter implements WalletAccountRepository {

  private final WalletAccountMapper accountMapper;
  private final WalletLedgerMapper ledgerMapper;

  public WalletAccountRepositoryAdapter(WalletAccountMapper accountMapper, WalletLedgerMapper ledgerMapper) {
    this.accountMapper = accountMapper;
    this.ledgerMapper = ledgerMapper;
  }

  @Override
  public Optional<WalletAccount> findByCustomerAndCurrency(Long customerId, String currencyCode) {
    return accountMapper.selectList(Wrappers.<CstWalletAccountPo>lambdaQuery()
            .eq(CstWalletAccountPo::getCustomerId, customerId)
            .eq(CstWalletAccountPo::getCurrencyCode, currencyCode)
            .last("LIMIT 1"))
        .stream().findFirst().map(WalletAccountRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<WalletAccount> findByCustomer(Long customerId) {
    return accountMapper.selectList(Wrappers.<CstWalletAccountPo>lambdaQuery()
            .eq(CstWalletAccountPo::getCustomerId, customerId)
            .last("LIMIT 1"))
        .stream().findFirst().map(WalletAccountRepositoryAdapter::toDomain);
  }

  @Override
  public Map<Long, WalletAccount> findByCustomers(List<Long> customerIds) {
    if (customerIds == null || customerIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, WalletAccount> result = new LinkedHashMap<>();
    for (CstWalletAccountPo po : accountMapper.selectList(Wrappers.<CstWalletAccountPo>lambdaQuery()
        .in(CstWalletAccountPo::getCustomerId, customerIds))) {
      result.put(po.getCustomerId(), toDomain(po));
    }
    return result;
  }

  @Override
  public WalletAccount insert(WalletAccount account) {
    LocalDateTime now = LocalDateTime.now();
    CstWalletAccountPo po = toPo(account);
    po.setAvailableAmount(account.available());
    po.setFrozenAmount(account.frozen());
    po.setVersion(0);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // 唯一键（会员 + 币种）冲突必须抛出：吞掉它会让调用方拿着未落库的账户继续扣款。
    accountMapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateBalanceWithVersion(WalletAccount account, int expectedVersion) {
    // CAS：判定与写入在同一条语句里。版本自增写在 SQL，避免用读出来的值加一而覆盖并发期间的版本号。
    //
    // 这一条是本文件存在的理由：原实现是 `bump(account); accountMapper.updateById(account)`，
    // 即无条件的整行覆盖。PO 上的 version 字段既没有 @Version 注解，工程里也没有
    // OptimisticLockerInnerInterceptor，所以 updateById 根本不会附加 version 条件 ——
    // version 被维护却从不参与判定，看起来有防护，实际没有。
    // 后果是两个并发扣减都读到同一余额、都判定"够扣"、各自算出自己的 balanceAfter 并写回：
    // 一笔钱被扣两次而账本只记一次成功。
    return accountMapper.update(null, Wrappers.<CstWalletAccountPo>lambdaUpdate()
        .eq(CstWalletAccountPo::getId, account.id())
        .eq(CstWalletAccountPo::getVersion, expectedVersion)
        .set(CstWalletAccountPo::getAvailableAmount, account.available())
        .set(CstWalletAccountPo::getFrozenAmount, account.frozen())
        .set(CstWalletAccountPo::getStatus, account.status())
        .set(CstWalletAccountPo::getUpdatedAt, LocalDateTime.now())
        // 只写真实列：tokenAmount / tokenBrandName / accountOpened 是 @TableField(exist = false) 的展示字段，
        // 不是列（可用余额 ÷ 100 × ratio 由 WalletTokenDisplayService 算出）。对它们 .set() 会让
        // MyBatis-Plus 报 "can not find lambda cache for this property" —— 运行时才炸，编译期看不出来。
        .setSql("version = version + 1")) > 0;
  }

  @Override
  public Optional<WalletAccount> findById(Long accountId) {
    if (accountId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(accountMapper.selectById(accountId))
        .map(WalletAccountRepositoryAdapter::toDomain);
  }

  @Override
  public void insertLedger(WalletLedgerEntry entry) {
    LocalDateTime now = LocalDateTime.now();
    CstWalletLedgerPo po = new CstWalletLedgerPo();
    po.setTenantId(entry.tenantId());
    po.setStoreId(entry.storeId());
    po.setWalletAccountId(entry.walletAccountId());
    po.setEntryType(entry.entryType());
    po.setAmount(entry.amountOrZero());
    po.setBalanceAfter(entry.balanceAfterOrZero());
    po.setCurrencyCode(entry.currencyCode());
    po.setTokenAmount(entry.tokenAmount());
    po.setTokenBrandName(entry.tokenBrandName());
    po.setOrderId(entry.orderId());
    po.setFxQuoteId(entry.fxQuoteId());
    po.setIdempotencyKey(entry.idempotencyKey());
    po.setOccurredAt(entry.occurredAt() == null ? now : entry.occurredAt());
    po.setCreatedAt(now);
    // 撞唯一键（tenant_id, idempotency_key）必须抛出：吞掉它会让调用方以为流水已落但实际没有。
    ledgerMapper.insert(po);
  }

  @Override
  public boolean ledgerExists(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return false;
    }
    return ledgerMapper.selectCount(Wrappers.<CstWalletLedgerPo>lambdaQuery()
        .eq(CstWalletLedgerPo::getIdempotencyKey, idempotencyKey)) > 0;
  }

  @Override
  public LedgerPage listLedger(Long walletAccountId, Long storeId, long offset, long limit) {
    List<WalletLedgerEntry> entries = ledgerMapper.selectList(
            Wrappers.<CstWalletLedgerPo>lambdaQuery()
                .eq(CstWalletLedgerPo::getWalletAccountId, walletAccountId)
                .eq(storeId != null, CstWalletLedgerPo::getStoreId, storeId)
                .orderByDesc(CstWalletLedgerPo::getId)
                .last("LIMIT " + Math.max(0L, limit) + " OFFSET " + Math.max(0L, offset)))
        .stream().map(WalletAccountRepositoryAdapter::toDomain).toList();
    return new LedgerPage(entries, countLedger(walletAccountId, storeId));
  }

  @Override
  public long countLedger(Long walletAccountId, Long storeId) {
    return ledgerMapper.selectCount(Wrappers.<CstWalletLedgerPo>lambdaQuery()
        .eq(CstWalletLedgerPo::getWalletAccountId, walletAccountId)
        .eq(storeId != null, CstWalletLedgerPo::getStoreId, storeId));
  }

  private static CstWalletAccountPo toPo(WalletAccount account) {
    CstWalletAccountPo po = new CstWalletAccountPo();
    po.setId(account.id());
    po.setTenantId(account.tenantId());
    po.setCustomerId(account.customerId());
    po.setLegalEntityId(account.legalEntityId());
    po.setCurrencyCode(account.currencyCode());
    po.setAvailableAmount(account.available());
    po.setFrozenAmount(account.frozen());
    po.setStatus(account.status());
    po.setVersion(account.version());
    // 不设 tokenAmount / tokenBrandName / accountOpened：它们是 @TableField(exist = false) 的展示字段，
    // 不落库，由 WalletTokenDisplayService 在响应期填充。
    return po;
  }

  private static WalletAccount toDomain(CstWalletAccountPo po) {
    return new WalletAccount(po.getId(), po.getTenantId(), po.getCustomerId(), po.getLegalEntityId(),
        po.getCurrencyCode(), po.getAvailableAmount(), po.getFrozenAmount(), po.getTokenAmount(),
        po.getTokenBrandName(), po.getAccountOpened(), po.getStatus(), po.getVersion());
  }

  private static WalletLedgerEntry toDomain(CstWalletLedgerPo po) {
    return new WalletLedgerEntry(po.getId(), po.getTenantId(), po.getStoreId(), po.getWalletAccountId(),
        po.getEntryType(), po.getAmount(), po.getBalanceAfter(), po.getCurrencyCode(),
        po.getTokenAmount(), po.getTokenBrandName(), po.getOrderId(), po.getFxQuoteId(),
        po.getIdempotencyKey(), po.getOccurredAt());
  }
}
