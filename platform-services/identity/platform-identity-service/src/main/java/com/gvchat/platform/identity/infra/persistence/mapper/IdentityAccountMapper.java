package com.gvchat.platform.identity.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.identity.infra.persistence.po.IdentityAccountPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdentityAccountMapper extends BaseMapper<IdentityAccountPo> {
}
