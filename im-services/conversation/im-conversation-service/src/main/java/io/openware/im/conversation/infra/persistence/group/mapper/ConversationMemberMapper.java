package io.openware.im.conversation.infra.persistence.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.conversation.infra.persistence.group.po.ConversationMemberPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMemberMapper extends BaseMapper<ConversationMemberPo> {
}
