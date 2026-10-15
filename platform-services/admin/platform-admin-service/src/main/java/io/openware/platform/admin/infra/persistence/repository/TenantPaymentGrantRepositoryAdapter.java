package io.openware.platform.admin.infra.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.admin.domain.menu.repository.TenantPaymentGrantRepository;
import io.openware.platform.admin.infra.persistence.mapper.TenantPaymentMethodMapper;
import io.openware.platform.admin.infra.persistence.po.TenantPaymentMethodPo;
import org.springframework.stereotype.Repository;

/**
 * {@link TenantPaymentGrantRepository} 的持久化适配器。
 *
 * <p>授权判定 = 该租户下存在"已授权"记录；计数在数据库完成，避免把整表读进内存只为判断有无。
 */
@Repository
public class TenantPaymentGrantRepositoryAdapter implements TenantPaymentGrantRepository {

  /** granted 列的"已授权"取值。 */
  private static final int GRANTED_YES = 1;

  private final TenantPaymentMethodMapper mapper;

  public TenantPaymentGrantRepositoryAdapter(TenantPaymentMethodMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean isGranted(Long tenantId, String method) {
    return mapper.selectCount(Wrappers.<TenantPaymentMethodPo>lambdaQuery()
        .eq(TenantPaymentMethodPo::getTenantId, tenantId)
        .eq(TenantPaymentMethodPo::getMethod, method)
        .eq(TenantPaymentMethodPo::getGranted, GRANTED_YES)) > 0;
  }
}
