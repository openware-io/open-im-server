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
   * 列出某租户的门店（按标识升序），可选按门店标识集合与业态过滤。
   *
   * <p>过滤语义收在端口里：调用方表达的是"这些门店里、这个业态的"，不是可拼接的查询条件。
   *
   * @param storeIds     门店标识集合；{@code null} 或空表示不限定
   * @param businessType 业态编码（应为已归一的大写）；{@code null} 或空白表示不限定
   */
  List<Store> listByTenant(Long tenantId, List<Long> storeIds, String businessType);

  /** 按标识读取门店。 */
  Optional<Store> findById(Long storeId);

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
