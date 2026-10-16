package io.openware.platform.tenant.domain.store.repository;

import io.openware.platform.tenant.domain.store.model.Store;
import java.util.List;
import java.util.Optional;

/**
 * 门店仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface StoreRepository {

  /**
   * 列出某租户的门店（按标识升序），可选按门店标识集合、业态与状态过滤。
   *
   * <p>过滤语义收在端口里：调用方表达的是"这些门店里、这个业态且这个状态的"，不是可拼接的查询条件。
   *
   * @param storeIds     门店标识集合；{@code null} 或空表示不限定
   * @param businessType 业态编码（应为已归一的大写）；{@code null} 或空白表示不限定
   * @param status       门店状态；{@code null} 或空白表示不限定
   */
  List<Store> listByTenant(Long tenantId, List<Long> storeIds, String businessType, String status);

  /** 列出某租户的门店，不按业态/状态过滤。 */
  default List<Store> listByTenant(Long tenantId, List<Long> storeIds, String businessType) {
    return listByTenant(tenantId, storeIds, businessType, null);
  }

  /** 按标识读取门店。 */
  Optional<Store> findById(Long storeId);

  /**
   * 把某租户下所有门店的默认币种改为目标币种，返回受影响门店数。
   *
   * <p>**只改币种列**：门店其它配置（时区、营业日切点等）不参与币种联动，
   * 用整体保存会把并发编辑改动的列一起写回。
   *
   * <p>已在目标币种的门店不产生写入（条件 UPDATE），因此返回的受影响行数可直接作为
   * "实际写穿了几家门店"上报审计。
   */
  int changeDefaultCurrency(Long tenantId, String targetCurrencyCode);

  /**
   * 按标识读取门店**归属的租户**，且**忽略租户拦截器**。
   *
   * <p>刻意独立于 {@link #findById}：调用方需要区分「门店不存在」与「门店存在但属于别的租户」，
   * 以便给出 404 与 403 两种不同语义，而不是把越权访问静默降级成 404。因此这次读取必须能看到
   * 跨租户的行，租户边界由调用方比较。
   *
   * @return 归属租户；门店不存在时为空
   */
  Optional<Long> findOwnerTenantId(Long storeId);

  /**
   * 更新门店的时区、营业日切点与业态，返回是否确实更新了一行。三列各自可选（{@code null} = 本次不动该列）。
   *
   * <p>只写这三列（不整体覆盖）：门店字段很多，用整体保存会把未提交的列一起写回，
   * 在并发编辑下会覆盖他人刚改的值。
   */
  boolean updateSchedule(Long storeId, String timezone, String businessDayCutoff, String businessType,
                         Long operatorId);

  /**
   * 校验业态编码是否存在于**启用的**业态字典中，命中时返回字典里的规范编码。
   *
   * <p>返回字典中的编码而不是入参：字典是业态口径的权威来源，调用方应使用字典里的写法（已统一大写），
   * 而不是自己 trim/大写后的猜测值。
   *
   * @return 字典中的编码；字典中没有该业态或已停用时返回 {@code null}
   */
  String findActiveBusinessTypeCode(String businessType);
}
