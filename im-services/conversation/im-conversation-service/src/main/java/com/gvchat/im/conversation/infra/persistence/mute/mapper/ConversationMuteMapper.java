package com.gvchat.im.conversation.infra.persistence.mute.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.conversation.infra.persistence.mute.po.ConversationMutePo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMuteMapper extends BaseMapper<ConversationMutePo> {
}
