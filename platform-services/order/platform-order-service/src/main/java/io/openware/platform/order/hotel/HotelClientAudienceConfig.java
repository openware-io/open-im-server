package io.openware.platform.order.hotel;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册酒店链路的 C/B audience 校验。 */
@Configuration
public class HotelClientAudienceConfig implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HotelClientAudienceInterceptor())
        .addPathPatterns("/admin/**", "/business/hotel/catalog/**");
  }
}
