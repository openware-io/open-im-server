package com.gvchat.im.conversation.infra.persistence.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.conversation.infra.persistence.group.po.ConversationGroupPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationGroupMapper extends BaseMapper<ConversationGroupPo> {
}
