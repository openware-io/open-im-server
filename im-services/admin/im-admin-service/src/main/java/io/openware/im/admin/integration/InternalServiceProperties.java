package io.openware.im.admin.integration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理端访问各内部服务（user / message / conversation）的地址配置。
 *
 * <p>原名 {@code io.openware.im.admin.points.UserPointsServiceProperties}，因积分能力已迁至 SaaS 而更名；
 * 该类承载的始终是通用内部服务地址，与积分无实际关系。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "internal.services.user")
public class InternalServiceProperties {
  private String baseUrl;
  private String messageBaseUrl;
  private String conversationBaseUrl;
}
