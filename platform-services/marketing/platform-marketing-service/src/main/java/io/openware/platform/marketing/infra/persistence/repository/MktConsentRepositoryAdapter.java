package io.openware.platform.marketing.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.marketing.domain.consent.model.MktConsent;
import io.openware.platform.marketing.domain.consent.repository.MktConsentRepository;
import io.openware.platform.marketing.infra.persistence.mapper.ConsentMapper;
import io.openware.platform.marketing.infra.persistence.po.MktConsentPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link MktConsentRepository} 的持久化适配器。
 *
 * <p>审计字段由本层填充：mkt_consent 的 created_at / updated_at 是 NOT NULL 无默认值，领域模型按
 * standards/10 不承载记账字段，漏填会让 INSERT 失败。createdBy/updatedBy 走建表默认值 0。
 *
 * <p>{@code occurredAt} 是业务时间（同意发生时刻），由领域模型携带并原样落库；{@code version} 同理，
 * 由领域模型的状态迁移负责自增。
 */
@Repository
public class MktConsentRepositoryAdapter implements MktConsentRepository {

  private final ConsentMapper mapper;

  public MktConsentRepositoryAdapter(ConsentMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<MktConsent> listByAccount(Long accountId) {
    return mapper.selectList(Wrappers.<MktConsentPo>lambdaQuery()
            .eq(MktConsentPo::getAccountId, accountId)
            .orderByDesc(MktConsentPo::getId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<MktConsent> findUnique(Long accountId, String consentType, String channel) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<MktConsentPo>lambdaQuery()
        .eq(MktConsentPo::getAccountId, accountId)
        .eq(MktConsentPo::getConsentType, consentType)
        .eq(MktConsentPo::getChannel, channel)
        .last("LIMIT 1"))).map(this::toDomain);
  }

  @Override
  public MktConsent save(MktConsent consent) {
    LocalDateTime now = LocalDateTime.now();
    MktConsentPo po = new MktConsentPo();
    po.setId(consent.id());
    po.setAccountId(consent.accountId());
    po.setTenantId(consent.tenantId());
    po.setConsentType(consent.consentType());
    po.setChannel(consent.channel());
    po.setPolicyVersion(consent.policyVersion());
    po.setGranted(consent.granted() ? 1 : 0);
    po.setSource(consent.source());
    po.setOccurredAt(consent.occurredAt());
    po.setWithdrawnAt(consent.withdrawnAt());
    po.setVersion(consent.version());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
    } else {
      // changeGrant 已把版本 +1；CAS 的期望值是它之前的版本。
      int expectedVersion = consent.version() == null ? 0 : consent.version() - 1;
      int updated = mapper.update(null, Wrappers.<MktConsentPo>lambdaUpdate()
          .eq(MktConsentPo::getId, consent.id())
          .eq(MktConsentPo::getVersion, expectedVersion)
          .set(MktConsentPo::getPolicyVersion, consent.policyVersion())
          .set(MktConsentPo::getGranted, consent.granted() ? 1 : 0)
          .set(MktConsentPo::getSource, consent.source())
          .set(MktConsentPo::getOccurredAt, consent.occurredAt())
          .set(MktConsentPo::getWithdrawnAt, consent.withdrawnAt())
          .set(MktConsentPo::getUpdatedAt, now)
          .setSql("version = version + 1"));
      if (updated == 0) {
        throw new IllegalStateException("MKT_CONSENT_VERSION_CONFLICT");
      }
    }
    return toDomain(po);
  }

  private MktConsent toDomain(MktConsentPo po) {
    return new MktConsent(po.getId(), po.getAccountId(), po.getTenantId(), po.getConsentType(),
        po.getChannel(), po.getPolicyVersion(), po.getGranted() != null && po.getGranted() == 1,
        po.getSource(), po.getOccurredAt(), po.getWithdrawnAt(), po.getVersion());
  }
}
