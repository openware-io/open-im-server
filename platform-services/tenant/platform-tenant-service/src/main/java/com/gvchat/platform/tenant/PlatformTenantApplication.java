package com.gvchat.platform.tenant;

import com.gvchat.infrastructure.audit.AuditClientConfig;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationFilter;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    AuditClientConfig.class,
    InternalServiceAuthenticationProperties.class,
    InternalServiceAuthentication.class,
    InternalServiceAuthenticationFilter.class
})
public class PlatformTenantApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformTenantApplication.class, args);
    }
}
