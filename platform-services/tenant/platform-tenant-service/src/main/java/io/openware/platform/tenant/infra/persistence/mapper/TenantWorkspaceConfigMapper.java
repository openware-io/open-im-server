package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.TenantWorkspaceConfigPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantWorkspaceConfigMapper extends BaseMapper<TenantWorkspaceConfigPo> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM tnt_workspace_config WHERE tenant_id = #{tenantId}")
    TenantWorkspaceConfigPo selectByTenantIgnoringTenant(@Param("tenantId") Long tenantId);
}
