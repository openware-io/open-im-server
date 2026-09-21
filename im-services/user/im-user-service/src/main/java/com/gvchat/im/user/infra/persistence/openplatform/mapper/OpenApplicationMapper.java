package com.gvchat.im.user.infra.persistence.openplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.openplatform.po.OpenApplicationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpenApplicationMapper extends BaseMapper<OpenApplicationPo> {
}
