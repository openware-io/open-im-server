package io.openware.platform.marketing.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.marketing.domain.campaign.model.MktCampaign;
import io.openware.platform.marketing.domain.campaign.repository.MktCampaignRepository;
import io.openware.platform.marketing.infra.persistence.mapper.CampaignMapper;
import io.openware.platform.marketing.infra.persistence.po.MktCampaignPo;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@link MktCampaignRepository} 的持久化适配器。
 *
 * <p>生效窗口与可见范围在这里落成 SQL 条件：窗口两端允许为空（该端不设界），可见范围是「平台级
 * （tenant_id 为空）或本租户」；租户为空时只取平台级活动。
 */
@Repository
public class MktCampaignRepositoryAdapter implements MktCampaignRepository {

  private final CampaignMapper mapper;

  public MktCampaignRepositoryAdapter(CampaignMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<MktCampaign> findAvailable(Long tenantId, LocalDateTime moment) {
    return mapper.selectList(Wrappers.<MktCampaignPo>lambdaQuery()
            .eq(MktCampaignPo::getStatus, MktCampaign.STATUS_ACTIVE)
            .and(w -> w.isNull(MktCampaignPo::getStartAt).or().le(MktCampaignPo::getStartAt, moment))
            .and(w -> w.isNull(MktCampaignPo::getEndAt).or().ge(MktCampaignPo::getEndAt, moment))
            .and(w -> {
              w.isNull(MktCampaignPo::getTenantId);
              if (tenantId != null) {
                w.or().eq(MktCampaignPo::getTenantId, tenantId);
              }
            })
            .orderByDesc(MktCampaignPo::getId))
        .stream().map(this::toDomain).toList();
  }

  private MktCampaign toDomain(MktCampaignPo po) {
    return new MktCampaign(po.getId(), po.getTenantId(), po.getCampaignType(), po.getName(),
        po.getStartAt(), po.getEndAt(), po.getBudgetAmount(), po.getFundingParty(), po.getStatus(),
        po.getRuleSnapshotJson(), po.getVersion());
  }
}
