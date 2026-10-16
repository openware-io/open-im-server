package io.openware.platform.customer.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.customer.domain.account.model.AccountPurgeCount;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link PointAccountRepository} 的持久化适配器。
 *
 * <p>核心是 {@link #updateBalanceWithVersion}：余额写入必须带版本条件，见该方法的说明。
 * 分页用显式 {@code LIMIT}/{@code OFFSET}，不把 MyBatis-Plus 的 {@code Page}/{@code IPage}
 * 泄漏到端口签名里。
 *
 * <p><b>列集合只来自真实 DDL</b>：两个 PO 上都没有 {@code @TableField(exist = false)} 字段
 * （逐字段核对过，见 §「字段集只来自真实 DDL 列」与 {@link PointLedgerEntry}），
 * 因此这里不需要像钱包适配器那样跳过展示字段。若将来给积分 PO 加展示字段，
 * 必须同步从 INSERT/UPDATE 的列引用里排除。
 */
@Repository
public class PointAccountRepositoryAdapter implements PointAccountRepository {

  private final PointAccountMapper accountMapper;
  private final PointLedgerMapper ledgerMapper;

  public PointAccountRepositoryAdapter(PointAccountMapper accountMapper, PointLedgerMapper ledgerMapper) {
    this.accountMapper = accountMapper;
    this.ledgerMapper = ledgerMapper;
  }

  @Override
  public Optional<PointAccount> findByCustomer(Long customerId) {
    return accountMapper.selectList(Wrappers.<CstPointAccountPo>lambdaQuery()
            .eq(CstPointAccountPo::getCustomerId, customerId)
            .last("LIMIT 1"))
        .stream().findFirst().map(PointAccountRepositoryAdapter::toDomain);
  }

  @Override
  public PointAccount insert(PointAccount account) {
    LocalDateTime now = LocalDateTime.now();
    CstPointAccountPo po = new CstPointAccountPo();
    po.setTenantId(account.tenantId());
    po.setCustomerId(account.customerId());
    po.setProgramId(account.programId());
    // 默认值在适配器兜底：调用方只表达"开一个账户"，零额与版本 0 是落库口径。
    po.setAvailablePoints(account.available());
    po.setFrozenPoints(account.frozen());
    po.setVersion(0);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // 唯一键（租户 + 会员 + 计划）冲突必须抛出：吞掉它会让调用方拿着未落库的账户继续改余额。
    accountMapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateBalanceWithVersion(PointAccount account, int expectedVersion) {
    // CAS：判定与写入在同一条语句里。版本自增写在 SQL，避免用读出来的值加一而覆盖并发期间的版本号。
    //
    // 这不是"顺手加固"：原实现本来就是带 version 条件的 UPDATE（见 PointApplicationService 的
    // updateAccountOptimistic），本适配器把它的形状原样保留下来——只写真实列，且不降级为无条件写入。
    return accountMapper.update(null, Wrappers.<CstPointAccountPo>lambdaUpdate()
        .eq(CstPointAccountPo::getId, account.id())
        .eq(CstPointAccountPo::getVersion, expectedVersion)
        .set(CstPointAccountPo::getAvailablePoints, account.available())
        .set(CstPointAccountPo::getFrozenPoints, account.frozen())
        .set(CstPointAccountPo::getUpdatedAt, LocalDateTime.now())
        .setSql("version = version + 1")) > 0;
  }

  @Override
  public void appendLedger(PointLedgerEntry entry) {
    LocalDateTime now = LocalDateTime.now();
    CstPointLedgerPo po = new CstPointLedgerPo();
    po.setTenantId(entry.tenantId());
    po.setStoreId(entry.storeId());
    po.setAccountId(entry.accountId());
    po.setEntryType(entry.entryType());
    po.setPoints(entry.pointsOrZero());
    po.setCurrencyCode(entry.currencyCode());
    po.setBalanceAfter(entry.balanceAfterOrZero());
    po.setBusinessType(entry.businessType());
    po.setBusinessId(entry.businessId());
    po.setIdempotencyKey(entry.idempotencyKey());
    po.setRuleSnapshotJson(entry.ruleSnapshotJson());
    po.setExpiresAt(entry.expiresAt());
    po.setOccurredAt(entry.occurredAt() == null ? now : entry.occurredAt());
    po.setCreatedAt(entry.createdAt() == null ? now : entry.createdAt());
    // 撞唯一键（tenant_id, idempotency_key）必须抛出：吞掉它会让调用方以为流水已落但实际没有。
    ledgerMapper.insert(po);
  }

  @Override
  public boolean ledgerExists(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return false;
    }
    return ledgerMapper.selectCount(Wrappers.<CstPointLedgerPo>lambdaQuery()
        .eq(CstPointLedgerPo::getIdempotencyKey, idempotencyKey)) > 0;
  }

  @Override
  public LedgerPage listLedger(Long accountId, Long storeId, long offset, long limit) {
    List<PointLedgerEntry> entries = ledgerMapper.selectList(
            Wrappers.<CstPointLedgerPo>lambdaQuery()
                .eq(CstPointLedgerPo::getAccountId, accountId)
                .eq(storeId != null, CstPointLedgerPo::getStoreId, storeId)
                .orderByDesc(CstPointLedgerPo::getId)
                .last("LIMIT " + Math.max(0L, limit) + " OFFSET " + Math.max(0L, offset)))
        .stream().map(PointAccountRepositoryAdapter::toDomain).toList();
    return new LedgerPage(entries, countLedger(accountId, storeId));
  }

  @Override
  public long countLedger(Long accountId, Long storeId) {
    return ledgerMapper.selectCount(Wrappers.<CstPointLedgerPo>lambdaQuery()
        .eq(CstPointLedgerPo::getAccountId, accountId)
        .eq(storeId != null, CstPointLedgerPo::getStoreId, storeId));
  }

  @Override
  public List<PointAccount> listAll() {
    return accountMapper.selectList(Wrappers.lambdaQuery()).stream()
        .map(PointAccountRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Map<Long, PointAccount> findByCustomers(List<Long> customerIds) {
    if (customerIds == null || customerIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, PointAccount> result = new LinkedHashMap<>();
    for (CstPointAccountPo po : accountMapper.selectList(Wrappers.<CstPointAccountPo>lambdaQuery()
        .in(CstPointAccountPo::getCustomerId, customerIds))) {
      result.putIfAbsent(po.getCustomerId(), toDomain(po));
    }
    return result;
  }

  /** 期间净变动：{@code points} 自带正负（不按 entryType 判方向），SQL 只按 {@code occurred_at} 与门店过滤。 */
  @Override
  public long deltaForPeriodByStores(LocalDateTime from, LocalDateTime to, List<Long> stores) {
    boolean filterStores = stores != null && !stores.isEmpty();
    long delta = 0L;
    for (CstPointLedgerPo po : ledgerMapper.selectList(Wrappers.<CstPointLedgerPo>lambdaQuery()
        .ge(from != null, CstPointLedgerPo::getOccurredAt, from)
        .le(to != null, CstPointLedgerPo::getOccurredAt, to)
        .in(filterStores, CstPointLedgerPo::getStoreId, stores))) {
      delta += po.getPoints() == null ? 0L : po.getPoints();
    }
    return delta;
  }

  @Override
  public AccountPurgeCount purgeAccountsFor(Long customerId) {
    if (customerId == null) {
      return AccountPurgeCount.none();
    }
    List<CstPointAccountPo> accounts = accountMapper.selectList(Wrappers.<CstPointAccountPo>lambdaQuery()
        .eq(CstPointAccountPo::getCustomerId, customerId));
    int ledgers = 0;
    for (CstPointAccountPo account : accounts) {
      // 只对零额账户删流水——这是原实现的行为，逐字段核对后原样保留：
      // 非零账户的流水不会被这条路径删掉，账面事实不会因为清理垃圾档案而被抹掉。
      boolean empty = (account.getAvailablePoints() == null ? 0L : account.getAvailablePoints()) == 0L
          && (account.getFrozenPoints() == null ? 0L : account.getFrozenPoints()) == 0L;
      if (empty) {
        ledgers += ledgerMapper.delete(Wrappers.<CstPointLedgerPo>lambdaQuery()
            .eq(CstPointLedgerPo::getAccountId, account.getId()));
      }
    }
    int deleted = accountMapper.delete(Wrappers.<CstPointAccountPo>lambdaQuery()
        .eq(CstPointAccountPo::getCustomerId, customerId));
    return new AccountPurgeCount(deleted, ledgers);
  }

  private static PointAccount toDomain(CstPointAccountPo po) {
    return new PointAccount(po.getId(), po.getTenantId(), po.getCustomerId(), po.getProgramId(),
        po.getAvailablePoints(), po.getFrozenPoints(), po.getVersion());
  }

  private static PointLedgerEntry toDomain(CstPointLedgerPo po) {
    return new PointLedgerEntry(po.getId(), po.getTenantId(), po.getStoreId(), po.getAccountId(),
        po.getEntryType(), po.getPoints(), po.getCurrencyCode(), po.getBalanceAfter(),
        po.getBusinessType(), po.getBusinessId(), po.getIdempotencyKey(), po.getRuleSnapshotJson(),
        po.getExpiresAt(), po.getOccurredAt(), po.getCreatedAt());
  }
}
