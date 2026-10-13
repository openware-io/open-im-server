package io.openware.infrastructure.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * AuditClient Bean 配置：各服务在启动类 {@code @Import(AuditClientConfig.class)} 后即可注入 AuditClient。
 *
 * <p>密钥口径：上报通道使用平台内部服务共享密钥（环境变量 {@code INTERNAL_SERVICE_AUTH_SECRET}，
 * 与 Audit 服务端 {@code internal.service-auth.secret} 同一取值）。
 * **不提供回退默认值**：所有环境（本地 Kind、Compose、ACK）都必须注入该环境变量，
 * 缺失即 fail-fast，避免仓库可见的弱密钥被静默启用；测试环境由 pplication-test.yml 提供占位值。
 */
@Configuration
public class AuditClientConfig {

  @Bean
  public AuditClient auditClient(
      @Value("${app.audit-service.base-url:http://localhost:4190}") String baseUrl,
      @Value("${internal.service-auth.secret}") String internalSecret,
      @Value("${app.audit-service.reporter-source:" + AuditClient.DEFAULT_REPORTER_SOURCE + "}") String reporterSource) {
    return new AuditClient(RestClient.builder().baseUrl(baseUrl).build(), internalSecret, reporterSource);
  }
}
