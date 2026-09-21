package com.gvchat.im.conversation.infra.persistence.secretchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.conversation.infra.persistence.secretchat.po.SecretChatPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecretChatMapper extends BaseMapper<SecretChatPo> {
}
