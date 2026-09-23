package io.openware.im.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.admin.infra.persistence.po.AdminConfigurationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminConfigurationMapper extends BaseMapper<AdminConfigurationPo> {
}
