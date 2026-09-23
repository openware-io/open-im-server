package io.openware.infrastructure.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * AuditClient Bean 配置：各服务在启动类 {@code @Import(AuditClientConfig.class)} 后即可注入 AuditClient。
 *
 * <p>密钥口径：上报通道使用平台内部服务共享密钥（环境变量 {@code INTERNAL_SERVICE_AUTH_SECRET}，
 * 与 Audit 服务端 {@code internal.service-auth.secret} 同一取值）。回退默认值仅用于本地联调
 * ——k8s/ACK 的 Deployment 目前未向 common-audit-service 注入该环境变量，若此处不给回退，
 * 上报方与服务端会因为「一边有值一边没有」而全部 401；生产接入该环境变量后应删除回退值。
 */
@Configuration
public class AuditClientConfig {

  /** 本地联调回退密钥：必须 >= 32 字符（服务端 InternalServiceAuthenticationProperties 校验）。 */
  private static final String DEV_FALLBACK_SECRET = "open-im-audit-channel-dev-secret-0001";

  @Bean
  public AuditClient auditClient(
      @Value("${app.audit-service.base-url:http://localhost:4190}") String baseUrl,
      @Value("${internal.service-auth.secret:" + DEV_FALLBACK_SECRET + "}") String internalSecret,
      @Value("${app.audit-service.reporter-source:" + AuditClient.DEFAULT_REPORTER_SOURCE + "}") String reporterSource) {
    return new AuditClient(RestClient.builder().baseUrl(baseUrl).build(), internalSecret, reporterSource);
  }
}
