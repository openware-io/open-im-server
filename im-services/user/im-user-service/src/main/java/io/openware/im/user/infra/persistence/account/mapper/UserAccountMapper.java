package io.openware.im.user.infra.persistence.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.persistence.account.po.UserAccountPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountPo> {
}
