package io.openware.im.user.infra.persistence.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.persistence.account.po.UserStatusOperationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserStatusOperationMapper extends BaseMapper<UserStatusOperationPo> {
}
