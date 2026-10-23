package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.RoleBindingPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleBindingMapper extends BaseMapper<RoleBindingPo> {
}
