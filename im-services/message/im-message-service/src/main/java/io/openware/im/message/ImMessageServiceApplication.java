package io.openware.im.message;

import io.openware.infrastructure.config.RedisConfig;
import io.openware.infrastructure.mq.MqInfrastructureConfig;
import io.openware.infrastructure.security.AuthenticationUserProjection;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
import io.openware.infrastructure.security.JwtAuthenticationFilter;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.infrastructure.security.JwtTokenProvider;
import io.openware.infrastructure.security.RestAuthenticationEntryPoint;
import io.openware.infrastructure.security.SecurityConfig;
import io.openware.im.message.config.MessagePersistenceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "io.openware.im.message")
@Import({
    MessagePersistenceConfig.class,
    RedisConfig.class,
    MqInfrastructureConfig.class,
    JwtProperties.class,
    JwtTokenProvider.class,
    AuthenticationUserProjection.class,
    JwtAuthenticationFilter.class,
    InternalServiceAuthenticationProperties.class,
    InternalServiceAuthentication.class,
    InternalServiceAuthenticationFilter.class,
    InternalServiceAuthenticationInterceptor.class,
    RestAuthenticationEntryPoint.class,
    SecurityConfig.class
})
public class ImMessageServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ImMessageServiceApplication.class, args);
  }
}
