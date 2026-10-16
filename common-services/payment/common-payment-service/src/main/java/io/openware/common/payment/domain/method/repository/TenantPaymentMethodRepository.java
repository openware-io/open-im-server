package io.openware.common.payment.domain.method.repository;

import io.openware.common.payment.domain.method.model.TenantPaymentMethod;
import java.util.List;
import java.util.Optional;

/**
 * 租户支付方式授权仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型（见 standards/10“端口命名与契约”）。
 */
public interface TenantPaymentMethodRepository {

  /** 按租户 + 支付方式读取配置。 */
  Optional<TenantPaymentMethod> findByMethod(Long tenantId, String method);

  /** 列出某租户的全部支付方式配置。 */
  List<TenantPaymentMethod> listByTenant(Long tenantId);

  /** 新增配置，返回落库后的聚合（含回填主键）。 */
  TenantPaymentMethod insert(TenantPaymentMethod method);

  /**
   * 保存对已有配置的修改（授权/用户开关）。
   *
   * <p>只写这两个开关列：配置的身份（租户 + 支付方式）不参与更新，
   * 整体保存会把身份键一起写回。
   */
  boolean updateSwitches(TenantPaymentMethod method);
}
