package com.gvchat.platform.resource.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.resource.infra.persistence.po.ResEventOutboxPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventOutboxMapper extends BaseMapper<ResEventOutboxPo> {
}
