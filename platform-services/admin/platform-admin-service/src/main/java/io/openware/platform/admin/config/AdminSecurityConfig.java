package io.openware.platform.admin.config;

import io.openware.platform.admin.infra.security.ConsAdminAuthenticationFilter;
import io.openware.platform.admin.infra.security.ConsAdminSessionStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SaaS 后台安全配置：BCrypt 密码编码器 + Redis 会话鉴权过滤器。 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<ConsAdminAuthenticationFilter> saaAdminAuthenticationFilter(
            ConsAdminSessionStore sessionStore) {
        FilterRegistrationBean<ConsAdminAuthenticationFilter> reg =
                new FilterRegistrationBean<>(new ConsAdminAuthenticationFilter(sessionStore));
        reg.addUrlPatterns("/admin/*", "/business/*");
        reg.setOrder(2);
        return reg;
    }
}
