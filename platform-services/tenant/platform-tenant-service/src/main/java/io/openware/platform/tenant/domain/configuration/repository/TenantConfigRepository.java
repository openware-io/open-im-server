package io.openware.platform.tenant.domain.configuration.repository;

import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import java.util.List;
import java.util.Optional;

/**
 * 租户/门店配置仓储端口（读写）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface TenantConfigRepository {

  /**
   * 按作用域读取单条配置：租户 + 门店 + 配置键。
   *
   * <p>{@code storeId == 0} 即租户级。作用域四元组之外还按 {@code businessType} 排序优先取不限业态的那条：
   * 同一个键在"不限业态"与"限定某业态"下各有一条时，未指定业态的调用方应拿到前者。
   */
  Optional<TenantConfig> findByScope(Long tenantId, Long storeId, String configKey);

  /** 列出某租户下某门店的配置（{@code storeId == 0} 即租户级）。 */
  List<TenantConfig> listByScope(Long tenantId, Long storeId);

  /**
   * 保存配置：存在则更新其值，不存在则以给定草稿新增；返回落库后的配置。
   *
   * <p>upsert 而不是 save：这个端口的使用场景是"把某个键设成某个值"，调用方不需要关心行是否存在。
   */
  TenantConfig upsert(TenantConfig draft);
}
