package io.openware.platform.identity.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** identity 过滤器注册：requestId 最早执行，覆盖拒绝路径。 */
@Configuration
public class IdentityFilterConfig {

    @Bean
    public FilterRegistrationBean<IdentityRequestIdFilter> identityRequestIdFilter() {
        FilterRegistrationBean<IdentityRequestIdFilter> registration =
                new FilterRegistrationBean<>(new IdentityRequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }
}
