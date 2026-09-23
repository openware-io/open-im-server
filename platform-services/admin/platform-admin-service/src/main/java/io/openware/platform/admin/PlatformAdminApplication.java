package io.openware.platform.admin;

import io.openware.infrastructure.audit.AuditClientConfig;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    AuditClientConfig.class,
    InternalServiceAuthenticationProperties.class,
    InternalServiceAuthentication.class,
    InternalServiceAuthenticationInterceptor.class
})
public class PlatformAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformAdminApplication.class, args);
    }
}
