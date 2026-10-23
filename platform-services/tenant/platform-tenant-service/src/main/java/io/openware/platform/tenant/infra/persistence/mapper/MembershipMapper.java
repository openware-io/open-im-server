package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.MembershipPo;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MembershipMapper extends BaseMapper<MembershipPo> {

  /**
   * 登录期跨租户查询：按 accountId 显式过滤，跳过租户拦截器。
   * 仅允许 identity 内部调用，不暴露公网。
   */
  @InterceptorIgnore(tenantLine = "true")
  @Select("SELECT * FROM tnt_membership /* tenant-agnostic: 登录期按显式 account_id 跨租户查询 */ "
      + "WHERE account_id = #{accountId} AND status = 'ACTIVE' ORDER BY tenant_id, membership_type")
  List<MembershipPo> selectActiveByAccountId(@Param("accountId") Long accountId);
}
