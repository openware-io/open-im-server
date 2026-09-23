package io.openware.im.user.infra.persistence.openplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.persistence.openplatform.po.UserOidcRedirectUriPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserOidcRedirectUriMapper extends BaseMapper<UserOidcRedirectUriPo> {
}
