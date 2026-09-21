package com.gvchat.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.payment.infra.persistence.po.DailyClosingPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DailyClosingMapper extends BaseMapper<DailyClosingPo> {
}
