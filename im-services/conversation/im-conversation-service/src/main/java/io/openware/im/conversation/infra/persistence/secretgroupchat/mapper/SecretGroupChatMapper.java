package io.openware.im.conversation.infra.persistence.secretgroupchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.conversation.infra.persistence.secretgroupchat.po.SecretGroupChatPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecretGroupChatMapper extends BaseMapper<SecretGroupChatPo> {
}
