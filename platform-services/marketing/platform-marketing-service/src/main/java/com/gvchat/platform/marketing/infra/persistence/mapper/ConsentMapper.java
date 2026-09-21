package com.gvchat.platform.marketing.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.marketing.infra.persistence.po.MktConsentPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConsentMapper extends BaseMapper<MktConsentPo> {
}
