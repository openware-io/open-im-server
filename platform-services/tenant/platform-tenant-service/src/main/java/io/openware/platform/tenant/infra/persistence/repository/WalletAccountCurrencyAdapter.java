package io.openware.platform.tenant.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.tenant.domain.currency.model.WalletAccount;
import io.openware.platform.tenant.domain.currency.repository.WalletAccountCurrencyPort;
import io.openware.platform.tenant.infra.persistence.mapper.WalletAccountCurrencyMapper;
import io.openware.platform.tenant.infra.persistence.po.WalletAccountCurrencyPo;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link WalletAccountCurrencyPort} 的持久化适配器。
 *
 * <p>这是 tenant 服务读写**另一限界上下文**（钱包）表的受限端口：只暴露币种联动所需的三个动作，
 * 不含余额增减/冻结。端口归属 tenant（standards/10“跨上下文读取”），因此适配器落在 tenant 的
 * infra 层，而不是反向依赖钱包上下文的服务实现。
 */
@Repository
public class WalletAccountCurrencyAdapter implements WalletAccountCurrencyPort {

  private final WalletAccountCurrencyMapper mapper;

  public WalletAccountCurrencyAdapter(WalletAccountCurrencyMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<WalletAccount> listNonZeroInOtherCurrency(Long tenantId, String targetCurrencyCode) {
    // "非零余额"在 SQL 里判定，不把全租户账户拉进内存再筛。
    return mapper.selectList(Wrappers.<WalletAccountCurrencyPo>lambdaQuery()
            .eq(WalletAccountCurrencyPo::getTenantId, tenantId)
            .ne(WalletAccountCurrencyPo::getCurrencyCode, targetCurrencyCode)
            .and(w -> w.ne(WalletAccountCurrencyPo::getAvailableAmount, 0L)
                .or().ne(WalletAccountCurrencyPo::getFrozenAmount, 0L)))
        .stream().map(WalletAccountCurrencyAdapter::toDomain).toList();
  }

  @Override
  public boolean existsForSubject(Long tenantId, Long customerId, Long legalEntityId,
                                  String targetCurrencyCode) {
    return mapper.selectCount(Wrappers.<WalletAccountCurrencyPo>lambdaQuery()
        .eq(WalletAccountCurrencyPo::getTenantId, tenantId)
        .eq(WalletAccountCurrencyPo::getCustomerId, customerId)
        .eq(WalletAccountCurrencyPo::getCurrencyCode, targetCurrencyCode)
        // legal_entity_id 可为 null：SQL 里 = NULL 永远不成立，必须显式 IS NULL，
        // 否则会把"确实已存在"误判成"不存在"，随后改写撞唯一键。
        .isNull(legalEntityId == null, WalletAccountCurrencyPo::getLegalEntityId)
        .eq(legalEntityId != null, WalletAccountCurrencyPo::getLegalEntityId, legalEntityId)) > 0;
  }

  @Override
  public void changeCurrency(Long walletAccountId, String targetCurrencyCode) {
    // 只改币种列与更新时间：金额数字不变。若同时折算金额，那正是 §2.2.2 禁止的静默改写真实资产。
    mapper.update(null, Wrappers.<WalletAccountCurrencyPo>lambdaUpdate()
        .eq(WalletAccountCurrencyPo::getId, walletAccountId)
        .set(WalletAccountCurrencyPo::getCurrencyCode, targetCurrencyCode)
        .set(WalletAccountCurrencyPo::getUpdatedAt, LocalDateTime.now()));
  }

  private static WalletAccount toDomain(WalletAccountCurrencyPo po) {
    return new WalletAccount(po.getId(), po.getTenantId(), po.getCustomerId(), po.getLegalEntityId(),
        po.getCurrencyCode(), po.getAvailableAmount(), po.getFrozenAmount());
  }
}
