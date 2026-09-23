package io.openware.common.payment.channel.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.payment.channel.infra.persistence.po.PayChannelProviderPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayChannelProviderMapper extends BaseMapper<PayChannelProviderPo> {
}
