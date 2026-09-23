package io.openware.platform.identity;

import io.openware.infrastructure.mq.MqInfrastructureConfig;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.infrastructure.security.JwtTokenProvider;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({JwtProperties.class, JwtTokenProvider.class, MqInfrastructureConfig.class,
    InternalServiceAuthenticationProperties.class, InternalServiceAuthentication.class,
    InternalServiceAuthenticationInterceptor.class})
public class PlatformIdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformIdentityApplication.class, args);
    }
}
