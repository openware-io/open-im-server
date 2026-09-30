package io.openware.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointRuleConfigPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PointRuleConfigMapper extends BaseMapper<CstPointRuleConfigPo> {
}
