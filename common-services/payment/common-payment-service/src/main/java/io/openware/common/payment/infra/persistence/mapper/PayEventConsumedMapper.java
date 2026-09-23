package io.openware.common.payment.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.infra.persistence.po.PayEventConsumedPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayEventConsumedMapper extends BaseMapper<PayEventConsumedPo> {
}
