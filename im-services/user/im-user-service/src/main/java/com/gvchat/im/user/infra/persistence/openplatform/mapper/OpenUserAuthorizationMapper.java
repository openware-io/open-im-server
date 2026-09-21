package com.gvchat.im.user.infra.persistence.openplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.openplatform.po.OpenUserAuthorizationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpenUserAuthorizationMapper extends BaseMapper<OpenUserAuthorizationPo> {
}
