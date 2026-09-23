package io.openware.platform.identity.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.identity.infra.persistence.po.OauthLinkPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OauthLinkMapper extends BaseMapper<OauthLinkPo> {
}
