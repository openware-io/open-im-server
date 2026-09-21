package com.gvchat.group.idaas.infra.security;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * IDaaS 跨域配置：门户（admin.dev.example.com）与 SaaS 后台（saas-admin.dev.example.com）
 * 从浏览器直连 IDaaS /idaas/** 接口，需放行其来源。
 *
 * <p>允许来源可经 {@code IDAAS_CORS_ALLOWED_ORIGINS}（逗号分隔）配置，默认保持 dev 域名白名单；
 * 部署环境按需追加集团域名或本地来源，避免硬编码单一列表。
 */
@Configuration
public class GroupIdaasCorsConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  public GroupIdaasCorsConfig(
      @Value("${idaas.cors.allowed-origins:https://admin.dev.example.com,https://saas-admin.dev.example.com,https://example.com,https://www.example.com}") String origins) {
    this.allowedOrigins = Arrays.stream(origins.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("X-Request-Id")
        .allowCredentials(true)
        .maxAge(3600);
  }
}
