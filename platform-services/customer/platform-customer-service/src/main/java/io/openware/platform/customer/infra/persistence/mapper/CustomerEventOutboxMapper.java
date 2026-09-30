package io.openware.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.customer.infra.persistence.po.CstEventOutboxPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CustomerEventOutboxMapper extends BaseMapper<CstEventOutboxPo> {
}
