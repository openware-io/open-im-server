package io.openware.common.media;

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
import io.openware.common.media.config.MediaProperties;
import io.openware.common.media.config.SupportPersistenceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "io.openware.common.media")
@EnableConfigurationProperties(MediaProperties.class)
@Import({
    SupportPersistenceConfig.class,
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
public class ImSupportServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ImSupportServiceApplication.class, args);
  }
}
