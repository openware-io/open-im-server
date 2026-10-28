package io.openware.platform.order.infra.persistence.repository;

import io.openware.platform.order.domain.inventory.repository.StoreScopeRepository;
import io.openware.platform.order.infra.persistence.mapper.StoreScopeMapper;
import io.openware.platform.order.infra.persistence.po.StoreScopePo;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link StoreScopeRepository} 的只读适配器：读取租户域 {@code tnt_store} 的门店作用域快照。
 *
 * <p><b>边界说明</b>：库存服务不拥有 {@code tnt_store}，这里只有一条只读窄查询（租户 + 门店 →
 * organization/business/status），没有写入、没有联表，也不把门店实体带进库存领域。用途只有一个：
 * 跨门店/跨业态调拨时，目标门店的组织与业态必须来自门店权威数据，不能由调用方随意声明。
 */
@Repository
public class StoreScopeRepositoryAdapter implements StoreScopeRepository {

  private final StoreScopeMapper mapper;

  public StoreScopeRepositoryAdapter(StoreScopeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<StoreScope> findActive(long tenantId, Long storeId) {
    if (storeId == null) {
      return Optional.empty();
    }
    StoreScopePo po = mapper.selectScope(tenantId, storeId);
    if (po == null) {
      return Optional.empty();
    }
    StoreScope scope = new StoreScope(po.getTenantId(), po.getStoreId(), po.getOrganizationId(),
        po.getBusinessType(), po.getStatus());
    return scope.active() ? Optional.of(scope) : Optional.empty();
  }
}
