package com.gvchat.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.payment.infra.persistence.po.ShiftPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShiftMapper extends BaseMapper<ShiftPo> {
}
