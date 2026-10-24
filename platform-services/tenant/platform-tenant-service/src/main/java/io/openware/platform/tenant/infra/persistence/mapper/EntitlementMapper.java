package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.openware.platform.tenant.infra.persistence.row.EntitlementRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 权益只读查询。 */
@Mapper
public interface EntitlementMapper {

  @InterceptorIgnore(tenantLine = "true")
  @Select("""
      SELECT business_type, status, source
      FROM tnt_business_entitlement
      WHERE tenant_id = #{tenantId} AND status = 'ACTIVE'
      ORDER BY business_type
      """)
  List<EntitlementRow> selectActive(@Param("tenantId") Long tenantId);
}
