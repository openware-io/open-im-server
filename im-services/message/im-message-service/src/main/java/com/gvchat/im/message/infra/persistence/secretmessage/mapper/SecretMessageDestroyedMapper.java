package com.gvchat.im.message.infra.persistence.secretmessage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.message.infra.persistence.secretmessage.po.SecretMessageDestroyedPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecretMessageDestroyedMapper extends BaseMapper<SecretMessageDestroyedPo> {
}
