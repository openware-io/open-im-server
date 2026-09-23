package io.openware.platform.order.infra.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only account-to-member lookup used to enforce consumer ownership in order queries. */
@Mapper
public interface CustomerLookupMapper {
  @Select("SELECT id FROM cst_member WHERE tenant_id = #{tenantId} AND account_id = #{accountId} LIMIT 1")
  Long findMemberId(@Param("tenantId") long tenantId, @Param("accountId") long accountId);
}
