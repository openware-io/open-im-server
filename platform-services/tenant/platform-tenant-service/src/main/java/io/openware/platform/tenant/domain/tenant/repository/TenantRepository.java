package io.openware.platform.tenant.domain.tenant.repository;

import io.openware.platform.tenant.domain.tenant.model.Tenant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 租户仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface TenantRepository {

  /**
   * 按租户编码读取租户（创建用例的幂等判据）。
   *
   * <p>编码上的唯一性由数据库约束保证，因此最多返回一条。
   */
  Optional<Tenant> findByCode(String tenantCode);

  /**
   * 列出创建时间落在闭区间内的租户（按标识倒序），两端为 {@code null} 表示该端不设界。
   *
   * <p>筛选口径是**创建时间**而非更新时间：平台管理的区间筛选语义是"这段时间新开了哪些租户"。
   */
  Optional<Tenant> findById(Long id);

  /** 更新租户状态（平台启用/停用）。 */
  Tenant updateStatus(Tenant tenant);

  List<Tenant> listCreatedBetween(LocalDateTime fromInclusive, LocalDateTime toInclusive);

  /** 新增租户，返回落库后的聚合（含回填主键与创建时刻）。 */
  Tenant insert(Tenant tenant);
}
