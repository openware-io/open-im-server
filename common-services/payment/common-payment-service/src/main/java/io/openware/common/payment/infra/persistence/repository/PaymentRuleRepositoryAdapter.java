package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.rule.model.ClosingRuleConfig;
import io.openware.common.payment.domain.rule.model.RefundRuleConfig;
import io.openware.common.payment.domain.rule.repository.PaymentRuleRepository;
import io.openware.common.payment.infra.persistence.mapper.PayDailyClosingRuleConfigMapper;
import io.openware.common.payment.infra.persistence.mapper.PayRefundRuleConfigMapper;
import io.openware.common.payment.infra.persistence.po.PayDailyClosingRuleConfigPo;
import io.openware.common.payment.infra.persistence.po.PayRefundRuleConfigPo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link PaymentRuleRepository} 的持久化适配器。
 *
 * <p>两处实现选择：租户级门店键用 {@code IS NULL}（{@code = NULL} 永不成立）；
 * 更新是单条 CAS（版本自增写在 SQL 里），不是"读出来加一再写回"。
 *
 * <p>审计字段由本层填充（{@code created_at}/{@code updated_at} 为 NOT NULL 无默认值；
 * {@code created_by}/{@code updated_by} 在 DDL 有默认值，保持默认 0）。
 */
@Repository
public class PaymentRuleRepositoryAdapter implements PaymentRuleRepository {

  private final PayRefundRuleConfigMapper refundMapper;
  private final PayDailyClosingRuleConfigMapper closingMapper;

  public PaymentRuleRepositoryAdapter(PayRefundRuleConfigMapper refundMapper,
                                      PayDailyClosingRuleConfigMapper closingMapper) {
    this.refundMapper = refundMapper;
    this.closingMapper = closingMapper;
  }

  @Override
  public Optional<RefundRuleConfig> findRefundRule(Long tenantId, String businessType, Long storeId) {
    return refundMapper.selectList(Wrappers.<PayRefundRuleConfigPo>lambdaQuery()
            .eq(PayRefundRuleConfigPo::getTenantId, tenantId)
            .eq(PayRefundRuleConfigPo::getBusinessType,
                businessType == null ? RefundRuleConfig.ANY_BUSINESS_TYPE : businessType)
            .eq(PayRefundRuleConfigPo::getStatus, RefundRuleConfig.STATUS_ACTIVE)
            .isNull(storeId == null, PayRefundRuleConfigPo::getStoreId)
            .eq(storeId != null, PayRefundRuleConfigPo::getStoreId, storeId))
        .stream().findFirst().map(PaymentRuleRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<ClosingRuleConfig> findClosingRule(Long tenantId, Long storeId) {
    return closingMapper.selectList(Wrappers.<PayDailyClosingRuleConfigPo>lambdaQuery()
            .eq(PayDailyClosingRuleConfigPo::getTenantId, tenantId)
            .eq(PayDailyClosingRuleConfigPo::getStoreId, storeId)
            .eq(PayDailyClosingRuleConfigPo::getStatus, ClosingRuleConfig.STATUS_ACTIVE))
        .stream().findFirst().map(PaymentRuleRepositoryAdapter::toDomain);
  }

  @Override
  public RefundRuleConfig insertRefundRule(RefundRuleConfig config) {
    LocalDateTime now = LocalDateTime.now();
    PayRefundRuleConfigPo po = toPo(config);
    po.setVersion(0);
    po.setStatus(RefundRuleConfig.STATUS_ACTIVE);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    refundMapper.insert(po);
    return toDomain(po);
  }

  @Override
  public ClosingRuleConfig insertClosingRule(ClosingRuleConfig config) {
    LocalDateTime now = LocalDateTime.now();
    PayDailyClosingRuleConfigPo po = new PayDailyClosingRuleConfigPo();
    po.setTenantId(config.tenantId());
    po.setStoreId(config.storeId());
    po.setClosingMinute(config.closingMinute());
    po.setIdempotencyKey(config.idempotencyKey());
    po.setVersion(0);
    po.setStatus(ClosingRuleConfig.STATUS_ACTIVE);
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    closingMapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateRefundRule(RefundRuleConfig config, int expectedVersion) {
    return refundMapper.update(null, Wrappers.<PayRefundRuleConfigPo>lambdaUpdate()
        .eq(PayRefundRuleConfigPo::getId, config.id())
        .eq(PayRefundRuleConfigPo::getVersion, expectedVersion)
        .set(PayRefundRuleConfigPo::getApprovalThreshold, config.approvalThreshold())
        .set(PayRefundRuleConfigPo::getOfflineRefundEnabled,
            Boolean.FALSE.equals(config.offlineRefundEnabled()) ? 0 : 1)
        .set(PayRefundRuleConfigPo::getIdempotencyKey, config.idempotencyKey())
        .set(PayRefundRuleConfigPo::getUpdatedAt, LocalDateTime.now())
        .setSql("version = version + 1")) > 0;
  }

  @Override
  public boolean updateClosingRule(ClosingRuleConfig config, int expectedVersion) {
    return closingMapper.update(null, Wrappers.<PayDailyClosingRuleConfigPo>lambdaUpdate()
        .eq(PayDailyClosingRuleConfigPo::getId, config.id())
        .eq(PayDailyClosingRuleConfigPo::getVersion, expectedVersion)
        .set(PayDailyClosingRuleConfigPo::getClosingMinute, config.closingMinute())
        .set(PayDailyClosingRuleConfigPo::getIdempotencyKey, config.idempotencyKey())
        .set(PayDailyClosingRuleConfigPo::getUpdatedAt, LocalDateTime.now())
        .setSql("version = version + 1")) > 0;
  }

  private static PayRefundRuleConfigPo toPo(RefundRuleConfig config) {
    PayRefundRuleConfigPo po = new PayRefundRuleConfigPo();
    po.setId(config.id());
    po.setTenantId(config.tenantId());
    po.setBusinessType(config.businessType());
    po.setStoreId(config.storeId());
    po.setApprovalThreshold(config.approvalThreshold());
    po.setOfflineRefundEnabled(Boolean.FALSE.equals(config.offlineRefundEnabled()) ? 0 : 1);
    po.setVersion(config.version());
    po.setIdempotencyKey(config.idempotencyKey());
    po.setStatus(config.status());
    return po;
  }

  private static RefundRuleConfig toDomain(PayRefundRuleConfigPo po) {
    return new RefundRuleConfig(po.getId(), po.getTenantId(), po.getBusinessType(), po.getStoreId(),
        po.getApprovalThreshold(), po.getOfflineRefundEnabled() == null
            ? null : po.getOfflineRefundEnabled() == 1, po.getVersion(), po.getIdempotencyKey(),
        po.getStatus());
  }

  private static ClosingRuleConfig toDomain(PayDailyClosingRuleConfigPo po) {
    return new ClosingRuleConfig(po.getId(), po.getTenantId(), po.getStoreId(), po.getClosingMinute(),
        po.getVersion(), po.getIdempotencyKey(), po.getStatus());
  }
}
