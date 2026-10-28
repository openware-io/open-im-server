package io.openware.platform.tenant.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 租户管理接口的 C/B audience 校验。 */
@Configuration
public class TenantAudienceConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new TenantAdminAudienceInterceptor())
        .addPathPatterns("/admin/**")
        .excludePathPatterns("/admin/platform/**");
  }
}
