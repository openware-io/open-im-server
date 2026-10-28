package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.AppInstallationPo;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppInstallationMapper extends BaseMapper<AppInstallationPo> {

  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM tnt_app_installation WHERE tenant_id = #{tenantId} AND app_id = #{appId}")
  AppInstallationPo selectByTenantAndApp(@Param("tenantId") Long tenantId, @Param("appId") String appId);

  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM tnt_app_installation WHERE tenant_id = #{tenantId} ORDER BY id")
  List<AppInstallationPo> selectByTenant(@Param("tenantId") Long tenantId);
}
