package io.openware.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.infra.persistence.po.DailyClosingPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DailyClosingMapper extends BaseMapper<DailyClosingPo> {
}
