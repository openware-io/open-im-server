package io.openware.platform.admin.config;

import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.admin.infra.AuditLogInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册审计拦截器：/admin/** 的写操作与导出/下载敏感读落统一审计表。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuditClient auditClient;

    public WebConfig(AuditClient auditClient) {
        this.auditClient = auditClient;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuditLogInterceptor(auditClient)).addPathPatterns("/admin/**");
    }
}
