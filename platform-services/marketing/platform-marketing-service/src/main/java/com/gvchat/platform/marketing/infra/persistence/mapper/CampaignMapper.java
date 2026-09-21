package com.gvchat.platform.marketing.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.marketing.infra.persistence.po.MktCampaignPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CampaignMapper extends BaseMapper<MktCampaignPo> {
}
