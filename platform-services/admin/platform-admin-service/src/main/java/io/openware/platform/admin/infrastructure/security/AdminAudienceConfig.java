package io.openware.platform.admin.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 管理端 path audience 校验。 */
@Configuration
public class AdminAudienceConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AdminAudienceInterceptor()).addPathPatterns("/admin/**");
  }
}
