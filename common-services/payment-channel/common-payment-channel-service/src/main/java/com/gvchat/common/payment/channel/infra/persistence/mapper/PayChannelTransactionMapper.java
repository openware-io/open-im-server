package com.gvchat.common.payment.channel.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.payment.channel.infra.persistence.po.PayChannelTransactionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayChannelTransactionMapper extends BaseMapper<PayChannelTransactionPo> {
}
