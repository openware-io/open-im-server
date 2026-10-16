package io.openware.platform.tenant.domain.configuration.repository;

import java.util.Optional;

/**
 * 租户配置**跨租户只读**端口。
 *
 * <p>与 {@link TenantConfigRepository} 分开，是因为两者的租户语义相反：
 * 常规仓储受签名上下文的租户约束，而本端口由**上下文签发路径**调用——那时还没有租户上下文，
 * 需要按显式入参 {@code tenantId} 读取任意租户的配置。实现必须跳过租户拦截器
 * （{@code @InterceptorIgnore}），否则只能读到"当前无租户"下的 0 行，币种会静默回退成缺省值。
 *
 * <p>刻意只提供单键读取：跨租户访问面越小越好，不给调用方按租户列举配置的能力。
 */
public interface TenantConfigReader {

  /**
   * 按显式租户读取配置值（忽略租户拦截器）。
   *
   * @return 配置值；无行或值为空时返回空，**不抛异常**——缺省回退由调用方决定
   */
  Optional<String> findValue(Long tenantId, long storeId, String configKey);
}
