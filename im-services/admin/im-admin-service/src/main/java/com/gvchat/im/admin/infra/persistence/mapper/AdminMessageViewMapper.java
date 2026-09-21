package com.gvchat.im.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.admin.infra.persistence.po.AdminMessageViewPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMessageViewMapper extends BaseMapper<AdminMessageViewPo> {
}
