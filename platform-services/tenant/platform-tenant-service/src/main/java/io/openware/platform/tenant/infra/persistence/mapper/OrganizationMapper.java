package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrganizationMapper {

  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT tenant_id FROM tnt_organization "
      + "/* tenant-agnostic: 按主键校验组织归属租户 */ WHERE id = #{id} AND status = 'ACTIVE'")
  Long selectTenantId(@Param("id") Long id);
}
