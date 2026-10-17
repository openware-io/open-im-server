package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.payment.domain.channel.repository.AuditSnapshot;
import io.openware.common.payment.domain.channel.repository.Persisted;
import io.openware.common.payment.domain.closing.model.DailyClosing;
import io.openware.common.payment.domain.closing.repository.DailyClosingRepository;
import io.openware.common.payment.infra.persistence.mapper.DailyClosingMapper;
import io.openware.common.payment.infra.persistence.po.DailyClosingPo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link DailyClosingRepository} 的持久化适配器。 */
@Repository
public class DailyClosingRepositoryAdapter implements DailyClosingRepository {

  private final DailyClosingMapper mapper;

  public DailyClosingRepositoryAdapter(DailyClosingMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<Persisted<DailyClosing>> listByBusinessDateBetween(LocalDate fromInclusive, LocalDate toInclusive) {
    // business_date 是 DATE 列：用日期直接比较，不包函数（保持索引可用），也不引入时分秒带来的边界运气。
    return mapper.selectList(Wrappers.<DailyClosingPo>lambdaQuery()
            .ge(fromInclusive != null, DailyClosingPo::getBusinessDate, fromInclusive)
            .le(toInclusive != null, DailyClosingPo::getBusinessDate, toInclusive)
            .orderByDesc(DailyClosingPo::getCreatedAt))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Optional<Persisted<DailyClosing>> findByStoreAndBusinessDate(Long tenantId, Long storeId,
                                                                      LocalDate businessDate) {
    return mapper.selectList(Wrappers.<DailyClosingPo>lambdaQuery()
            .eq(DailyClosingPo::getTenantId, tenantId)
            .eq(DailyClosingPo::getStoreId, storeId)
            .eq(DailyClosingPo::getBusinessDate, businessDate))
        .stream().findFirst().map(this::toPersisted);
  }

  @Override
  public Persisted<DailyClosing> insert(DailyClosing closing, LocalDateTime occurredAt) {
    DailyClosingPo po = new DailyClosingPo();
    po.setTenantId(closing.tenantId());
    po.setStoreId(closing.storeId());
    po.setBusinessDate(closing.businessDate());
    po.setSubmittedBy(closing.submittedBy());
    po.setCurrencyCode(closing.currencyCode());
    po.setSummaryJson(closing.summaryJson());
    po.setStatus(closing.status());
    po.setCreatedAt(occurredAt);
    po.setUpdatedAt(occurredAt);
    mapper.insert(po);
    return toPersisted(po);
  }

  private Persisted<DailyClosing> toPersisted(DailyClosingPo po) {
    return new Persisted<>(toDomain(po), new AuditSnapshot(po.getCreatedAt(), po.getUpdatedAt()));
  }

  private static DailyClosing toDomain(DailyClosingPo po) {
    return new DailyClosing(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessDate(),
        po.getSubmittedBy(), po.getReviewedBy(), po.getCurrencyCode(), po.getSummaryJson(),
        po.getStatus());
  }
}
