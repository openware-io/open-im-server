package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.AppInstallationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppInstallationMapper extends BaseMapper<AppInstallationPo> {
}
