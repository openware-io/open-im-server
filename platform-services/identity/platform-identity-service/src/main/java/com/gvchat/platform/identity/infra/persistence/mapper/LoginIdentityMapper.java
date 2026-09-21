package com.gvchat.platform.identity.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.identity.infra.persistence.po.LoginIdentityPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginIdentityMapper extends BaseMapper<LoginIdentityPo> {
}
