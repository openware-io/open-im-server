package io.openware.im.message.infra.persistence.secretgroupmessage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.secretgroupmessage.po.SecretGroupMessageDestroyedPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecretGroupMessageDestroyedMapper extends BaseMapper<SecretGroupMessageDestroyedPo> {
}
