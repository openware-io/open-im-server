package io.openware.platform.customer.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PointLedgerMapper extends BaseMapper<CstPointLedgerPo> {
}
