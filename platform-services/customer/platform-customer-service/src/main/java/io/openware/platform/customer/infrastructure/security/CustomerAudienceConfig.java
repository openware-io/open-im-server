package io.openware.platform.customer.infrastructure.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 客户 C 端路径 audience 校验。 */
@Configuration
public class CustomerAudienceConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new CustomerAudienceInterceptor()).addPathPatterns("/business/**");
  }
}
