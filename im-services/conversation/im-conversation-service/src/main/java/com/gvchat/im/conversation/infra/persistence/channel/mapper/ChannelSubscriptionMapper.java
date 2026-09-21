package com.gvchat.im.conversation.infra.persistence.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.conversation.infra.persistence.channel.po.ChannelSubscriptionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChannelSubscriptionMapper extends BaseMapper<ChannelSubscriptionPo> {
}
