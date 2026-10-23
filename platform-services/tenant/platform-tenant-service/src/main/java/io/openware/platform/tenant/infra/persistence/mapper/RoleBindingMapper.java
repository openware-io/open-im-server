package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.RoleBindingPo;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RoleBindingMapper extends BaseMapper<RoleBindingPo> {

  /** 平台运营的 ACCOUNT 绑定 id（跨租户）。 */
  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT id FROM iam_role_binding /* tenant-agnostic: 平台 ACCOUNT 绑定 */ "
      + "WHERE subject_type = 'ACCOUNT' AND subject_id = #{accountId} "
      + "AND scope_type = 'PLATFORM' AND status = 'ACTIVE' ORDER BY id")
  List<Long> selectPlatformIdsByAccount(@Param("accountId") Long accountId);

  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT id, subject_type, subject_id, tenant_id, scope_type, authorization_version "
      + "FROM iam_role_binding /* tenant-agnostic: 按主键读取安全描述 */ WHERE id = #{id}")
  RoleBindingPo selectDescriptorById(@Param("id") Long id);

  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT CASE WHEN subject_type = 'ACCOUNT' THEN subject_id "
      + "ELSE (SELECT account_id FROM tnt_membership /* tenant-agnostic: 绑定主键回查 */ WHERE id = subject_id) END "
      + "FROM iam_role_binding /* tenant-agnostic: 按绑定主键回查账号 */ WHERE id = #{id}")
  Long selectAccountIdByAnyId(@Param("id") Long id);

  @InterceptorIgnore(tenantLine = "true")
  @Update("UPDATE iam_role_binding /* tenant-agnostic: 按绑定主键撤销 */ SET status = 'REVOKED', updated_at = NOW(3) "
      + "WHERE id = #{id} AND status = 'ACTIVE'")
  int revokeAnyById(@Param("id") Long id);
}
