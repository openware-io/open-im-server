package io.openware.common.payment.config;

import io.openware.infrastructure.tenant.TenantContextFilter;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.common.payment.infra.client.TenantAuthorizationStateClient;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 注册租户上下文过滤器：从 X-Tenant-Context 头解析并注入 TenantContextHolder。
 * 领域服务在上下文之上再做权限码 + 数据范围校验（SAAS_PLATFORM_02 §4.1）。
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class TenantFilterConfig {
    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter(JwtProperties jwtProperties) {
        FilterRegistrationBean<TenantContextFilter> reg = new FilterRegistrationBean<>(new TenantContextFilter(jwtProperties));
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<TenantAuthorizationStateFilter> tenantAuthorizationStateFilter(
            TenantAuthorizationStateClient stateClient) {
        FilterRegistrationBean<TenantAuthorizationStateFilter> reg =
                new FilterRegistrationBean<>(new TenantAuthorizationStateFilter(stateClient));
        reg.addUrlPatterns("/*");
        reg.setOrder(2);
        return reg;
    }
}
