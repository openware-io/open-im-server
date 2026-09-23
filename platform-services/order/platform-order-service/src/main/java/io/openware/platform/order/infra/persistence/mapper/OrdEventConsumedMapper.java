package io.openware.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.order.infra.persistence.po.OrdEventConsumedPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrdEventConsumedMapper extends BaseMapper<OrdEventConsumedPo> {
}
