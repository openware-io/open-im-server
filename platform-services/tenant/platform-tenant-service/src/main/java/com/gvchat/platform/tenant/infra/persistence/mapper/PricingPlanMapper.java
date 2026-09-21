package com.gvchat.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.tenant.infra.persistence.po.PricingPlanPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PricingPlanMapper extends BaseMapper<PricingPlanPo> {
}
