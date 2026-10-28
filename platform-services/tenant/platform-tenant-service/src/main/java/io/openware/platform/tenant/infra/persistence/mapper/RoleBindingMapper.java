package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.RoleBindingPo;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
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
  @Insert("""
      INSERT INTO iam_role_binding /* tenant-agnostic: platform ACCOUNT binding */
        (subject_type, subject_id, role_id, scope_type, tenant_id, organization_id, store_id,
         authorization_version, status, created_at, updated_at)
      VALUES ('ACCOUNT', #{accountId}, #{roleId}, 'PLATFORM', 0, 0, 0, 1, 'ACTIVE', NOW(3), NOW(3))
      """)
  int insertPlatform(@Param("accountId") Long accountId, @Param("roleId") Long roleId);

  @InterceptorIgnore(tenantLine = "true")
  @Select("""
      SELECT id FROM iam_role_binding /* tenant-agnostic: platform ACCOUNT binding */
      WHERE subject_type = 'ACCOUNT' AND subject_id = #{accountId} AND role_id = #{roleId}
        AND scope_type = 'PLATFORM' AND status = 'ACTIVE'
      LIMIT 1
      """)
  Long selectPlatformId(@Param("accountId") Long accountId, @Param("roleId") Long roleId);

  @InterceptorIgnore(tenantLine = "true")
  @Update("""
      UPDATE iam_role_binding
      SET status = 'REVOKED', authorization_version = authorization_version + 1, updated_at = NOW(3)
      WHERE id = #{id} AND subject_type = 'ACCOUNT' AND scope_type = 'PLATFORM' AND status = 'ACTIVE'
      """)
  int revokePlatform(@Param("id") Long id);

  @InterceptorIgnore(tenantLine = "true")
  @Select("""
      SELECT CASE WHEN subject_type = 'ACCOUNT' THEN subject_id
        ELSE (SELECT account_id FROM tnt_membership /* tenant-agnostic: bind id lookup */ WHERE id = subject_id) END
      FROM iam_role_binding /* tenant-agnostic: bind id lookup */ WHERE id = #{id}
      """)
  Long selectAccountIdByAnyId(@Param("id") Long id);

  @InterceptorIgnore(tenantLine = "true")
  @Update("UPDATE iam_role_binding /* tenant-agnostic: 按绑定主键撤销 */ "
      + "SET status = 'REVOKED', authorization_version = authorization_version + 1, updated_at = NOW(3) "
      + "WHERE id = #{id} AND status = 'ACTIVE'")
  int revokeAnyById(@Param("id") Long id);
}
