package io.openware.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.message.po.MessageOutboxPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageOutboxMapper extends BaseMapper<MessageOutboxPo> {
}
