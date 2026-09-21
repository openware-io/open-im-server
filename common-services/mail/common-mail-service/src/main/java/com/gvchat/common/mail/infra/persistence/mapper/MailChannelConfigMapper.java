package com.gvchat.common.mail.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.mail.infra.persistence.po.MailChannelConfigPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MailChannelConfigMapper extends BaseMapper<MailChannelConfigPo> {
}
