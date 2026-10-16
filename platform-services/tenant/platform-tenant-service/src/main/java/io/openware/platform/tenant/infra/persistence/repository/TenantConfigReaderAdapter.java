package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.configuration.repository.TenantConfigReader;
import io.openware.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link TenantConfigReader} 的持久化适配器。
 *
 * <p>复用 {@link InternalTenantConfigMapper#selectStoreConfigValue}：它已带
 * {@code @InterceptorIgnore(tenantLine = "true")}，正是本端口"按显式租户读取、忽略上下文租户"的语义。
 * 不用 lambda Wrapper 重写一遍——那会绕开这个注解，在有租户上下文时静默过滤成 0 行。
 */
@Repository
public class TenantConfigReaderAdapter implements TenantConfigReader {

  private final InternalTenantConfigMapper mapper;

  public TenantConfigReaderAdapter(InternalTenantConfigMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<String> findValue(Long tenantId, long storeId, String configKey) {
    if (tenantId == null) {
      return Optional.empty();
    }
    String value = mapper.selectStoreConfigValue(tenantId, storeId, configKey);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }
}
