package com.gvchat.platform.admin.config;

import com.gvchat.platform.admin.infra.security.SaaAdminAuthenticationFilter;
import com.gvchat.platform.admin.infra.security.SaaAdminSessionStore;
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
    public FilterRegistrationBean<SaaAdminAuthenticationFilter> saaAdminAuthenticationFilter(
            SaaAdminSessionStore sessionStore) {
        FilterRegistrationBean<SaaAdminAuthenticationFilter> reg =
                new FilterRegistrationBean<>(new SaaAdminAuthenticationFilter(sessionStore));
        reg.addUrlPatterns("/admin/*", "/business/*");
        reg.setOrder(2);
        return reg;
    }
}
