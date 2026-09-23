package io.openware.platform.identity.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.identity.infra.persistence.po.LoginIdentityPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginIdentityMapper extends BaseMapper<LoginIdentityPo> {
}
