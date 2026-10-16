package io.openware.platform.tenant.domain.configuration.repository;

import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.domain.configuration.model.TenantConfigValue;
import java.util.List;
import java.util.Optional;

/**
 * 租户/门店/业态配置仓储端口（读写，受签名上下文的租户约束）。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 *
 * <p>跨租户只读的解析路径不走本端口，见 {@link TenantConfigReader}。
 */
public interface TenantConfigRepository {

  /**
   * 按作用域读取单条配置：租户 + 门店 + 业态 + 配置键。
   *
   * <p><b>业态必须参与定位</b>：同一个键在"不限业态"与"某业态"下可以各有一条，
   * 若查询漏掉业态列，为 A 业态写入的营业时间会覆盖掉不限业态的那条（反之亦然）。
   *
   * @param storeId      门店；0 表示租户级
   * @param businessType 业态；{@code null} 表示不限业态（按空串匹配）
   */
  Optional<TenantConfig> findByScope(Long tenantId, Long storeId, String businessType, String configKey);

  /** 列出某租户下某门店的配置（{@code storeId == 0} 即租户级）。 */
  List<TenantConfig> listByScope(Long tenantId, Long storeId);

  /**
   * 保存配置：按作用域四元组存在则更新其值，否则以给定草稿新增；返回落库后的配置。
   *
   * <p>upsert 而不是 save：这个端口的使用场景是"把某个键设成某个值"，调用方不需要关心行是否存在。
   */
  TenantConfig upsert(TenantConfig draft);

  /**
   * 读取某门店在该业态下的生效配置值（**忽略租户拦截器**，按显式入参定位）。
   *
   * <p>三个读方法对应"门店覆盖 > 业态默认 > 租户默认"的三层回退，各自只能取一层：
   * 把它们合成一个"按优先级返回"的方法会把回退策略搬进适配器，而回退顺序是业务口径。
   */
  Optional<TenantConfigValue> findStoreValue(Long tenantId, Long storeId, String businessType, String configKey);

  /** 读取某业态的默认配置值（**忽略租户拦截器**）。 */
  Optional<TenantConfigValue> findBusinessValue(Long tenantId, String businessType, String configKey);

  /** 读取租户级默认配置值（**忽略租户拦截器**）。 */
  Optional<TenantConfigValue> findTenantValue(Long tenantId, String configKey);
}
