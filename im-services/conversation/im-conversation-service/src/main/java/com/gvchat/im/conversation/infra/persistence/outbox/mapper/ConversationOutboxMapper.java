package com.gvchat.im.conversation.infra.persistence.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.conversation.infra.persistence.outbox.po.ConversationOutboxPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationOutboxMapper extends BaseMapper<ConversationOutboxPo> {
}
