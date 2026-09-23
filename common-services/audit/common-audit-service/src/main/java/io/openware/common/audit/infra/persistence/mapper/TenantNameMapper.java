package io.openware.common.audit.infra.persistence.mapper;

import io.openware.common.audit.infra.persistence.row.TenantNameRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户名称只读查询：审计列表需要返回 {@code tenantName}（平台视角尤其必要）。
 *
 * <p>审计表已迁到独立 schema {@code open_audit}（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md），
 * 因此这里**必须显式限定 schema**（{@code open_saas.tnt_tenant}）——不能再依赖连接默认库。
 * 该跨域只读是既有实现（不在审计表里冗余租户名，避免改名后历史记录与水印不一致），
 * 本次只保证迁移后仍可用，并已在部署脚本中单独授权只读。
 */
@Mapper
public interface TenantNameMapper {

    @Select("""
        <script>
        SELECT id, name FROM open_saas.tnt_tenant
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<TenantNameRow> selectNamesByIds(@Param("ids") List<Long> ids);
}
