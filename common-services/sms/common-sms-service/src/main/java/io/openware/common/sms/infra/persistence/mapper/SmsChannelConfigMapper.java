package io.openware.common.sms.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.sms.infra.persistence.po.SmsChannelConfigPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmsChannelConfigMapper extends BaseMapper<SmsChannelConfigPo> {
}
