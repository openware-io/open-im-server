package com.gvchat.im.message.infra.persistence.secretgroupmessage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessageDestroyedPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecretGroupMessageDestroyedMapper extends BaseMapper<SecretGroupMessageDestroyedPo> {
}
