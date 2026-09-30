package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.OrdReservationRuleConfigPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrdReservationRuleConfigMapper extends BaseMapper<OrdReservationRuleConfigPo> {
}
