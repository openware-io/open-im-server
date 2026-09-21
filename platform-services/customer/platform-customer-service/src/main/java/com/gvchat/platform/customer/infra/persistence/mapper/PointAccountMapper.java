package com.gvchat.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstPointAccountPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PointAccountMapper extends BaseMapper<CstPointAccountPo> {
}
