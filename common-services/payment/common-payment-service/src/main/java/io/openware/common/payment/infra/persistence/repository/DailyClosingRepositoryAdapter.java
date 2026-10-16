package io.openware.common.payment.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
  public List<DailyClosing> listByBusinessDateBetween(LocalDate fromInclusive, LocalDate toInclusive) {
    // business_date 是 DATE 列：用日期直接比较，不包函数（保持索引可用），也不引入时分秒带来的边界运气。
    return mapper.selectList(Wrappers.<DailyClosingPo>lambdaQuery()
            .ge(fromInclusive != null, DailyClosingPo::getBusinessDate, fromInclusive)
            .le(toInclusive != null, DailyClosingPo::getBusinessDate, toInclusive)
            .orderByDesc(DailyClosingPo::getCreatedAt))
        .stream().map(DailyClosingRepositoryAdapter::toDomain).toList();
  }

  @Override
  public Optional<DailyClosing> findByStoreAndBusinessDate(Long tenantId, Long storeId,
                                                           LocalDate businessDate) {
    return mapper.selectList(Wrappers.<DailyClosingPo>lambdaQuery()
            .eq(DailyClosingPo::getTenantId, tenantId)
            .eq(DailyClosingPo::getStoreId, storeId)
            .eq(DailyClosingPo::getBusinessDate, businessDate))
        .stream().findFirst().map(DailyClosingRepositoryAdapter::toDomain);
  }

  @Override
  public DailyClosing insert(DailyClosing closing) {
    LocalDateTime now = LocalDateTime.now();
    DailyClosingPo po = new DailyClosingPo();
    po.setTenantId(closing.tenantId());
    po.setStoreId(closing.storeId());
    po.setBusinessDate(closing.businessDate());
    po.setSubmittedBy(closing.submittedBy());
    po.setCurrencyCode(closing.currencyCode());
    po.setSummaryJson(closing.summaryJson());
    po.setStatus(closing.status());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    mapper.insert(po);
    return toDomain(po);
  }

  private static DailyClosing toDomain(DailyClosingPo po) {
    return new DailyClosing(po.getId(), po.getTenantId(), po.getStoreId(), po.getBusinessDate(),
        po.getSubmittedBy(), po.getReviewedBy(), po.getCurrencyCode(), po.getSummaryJson(),
        po.getStatus(), po.getCreatedAt(), po.getUpdatedAt());
  }
}
