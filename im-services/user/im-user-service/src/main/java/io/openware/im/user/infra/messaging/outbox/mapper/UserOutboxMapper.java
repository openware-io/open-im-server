package io.openware.im.user.infra.messaging.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.messaging.outbox.po.UserOutboxPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserOutboxMapper extends BaseMapper<UserOutboxPo> {
}
