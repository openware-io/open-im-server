package io.openware.common.payment.channel.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.channel.infra.persistence.po.PayChannelTransactionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayChannelTransactionMapper extends BaseMapper<PayChannelTransactionPo> {
}
