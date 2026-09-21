package com.gvchat.platform.marketing.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.marketing.infra.persistence.po.MktEventOutboxPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventOutboxMapper extends BaseMapper<MktEventOutboxPo> {
}
