package io.openware.platform.order;

import io.openware.infrastructure.audit.AuditClientConfig;
import io.openware.infrastructure.mq.MqInfrastructureConfig;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@Import({AuditClientConfig.class, MqInfrastructureConfig.class,
        InternalServiceAuthenticationProperties.class, InternalServiceAuthentication.class,
        InternalServiceAuthenticationInterceptor.class, InternalServiceAuthenticationFilter.class})
public class PlatformOrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformOrderApplication.class, args);
    }
}
