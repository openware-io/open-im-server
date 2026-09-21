package com.gvchat.common.audit.config;

import com.gvchat.common.audit.infra.security.AuditContextFilter;
import com.gvchat.common.audit.infra.security.InternalAuditAuthenticationFilter;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审计服务 Web 过滤器装配：
 * <ol>
 *   <li>{@link AuditContextFilter}：解析签名运营上下文（租户/平台视角），顺序 1；</li>
 *   <li>{@link InternalAuditAuthenticationFilter}：校验 {@code /internal/**} 的内部服务 HMAC 签名，顺序 2。</li>
 * </ol>
 *
 * <p><b>不要在本类上再写 {@code @EnableConfigurationProperties(InternalServiceAuthenticationProperties.class)}</b>：
 * 该 properties 类自带 {@code @Component}，启动类又用 {@code @Import} 注册过一次（bean 名是类全限定名），
 * 而 {@code @EnableConfigurationProperties} 会按「前缀 + 全限定名」再注册一个同名类型但不同名的 Bean，
 * 于是 {@link InternalServiceAuthentication} 的构造器看到 2 个候选 Bean，整个审计服务启动失败
 * （实测：{@code Parameter 0 of constructor ... required a single bean, but 2 were found}）。
 */
@Configuration
public class AuditWebConfig {

    @Bean
    public FilterRegistrationBean<AuditContextFilter> auditContextFilter(@Value("${jwt.secret:}") String jwtSecret) {
        FilterRegistrationBean<AuditContextFilter> registration =
                new FilterRegistrationBean<>(new AuditContextFilter(jwtSecret));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<InternalAuditAuthenticationFilter> internalAuditAuthenticationFilter(
            InternalServiceAuthentication authentication) {
        FilterRegistrationBean<InternalAuditAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new InternalAuditAuthenticationFilter(authentication));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }
}
