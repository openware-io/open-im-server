package io.openware.im.message.infra.persistence.secretmessage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.secretmessage.po.SecretMessagePo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecretMessageMapper extends BaseMapper<SecretMessagePo> {
}
