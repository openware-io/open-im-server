package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 用户应用授权（SaaS consumer 授权）只读查询。 */
@Mapper
public interface AppGrantMapper {

  @InterceptorIgnore(tenantLine = "true")
  @Select("""
      SELECT app_id
      FROM tnt_app_user_authorization
      WHERE account_id = #{accountId} AND tenant_id = #{tenantId} AND status = 'ACTIVE'
      """)
  List<String> selectAuthorizedApps(@Param("accountId") Long accountId,
                                    @Param("tenantId") Long tenantId);
}
