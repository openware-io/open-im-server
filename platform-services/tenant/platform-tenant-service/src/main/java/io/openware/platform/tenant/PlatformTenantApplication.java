package io.openware.platform.tenant;

import io.openware.infrastructure.audit.AuditClientConfig;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
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
