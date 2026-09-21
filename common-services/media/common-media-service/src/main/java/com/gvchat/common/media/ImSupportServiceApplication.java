package com.gvchat.common.media;

import com.gvchat.infrastructure.security.AuthenticationUserProjection;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationFilter;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import com.gvchat.infrastructure.security.JwtAuthenticationFilter;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.infrastructure.security.JwtTokenProvider;
import com.gvchat.infrastructure.security.RestAuthenticationEntryPoint;
import com.gvchat.infrastructure.security.SecurityConfig;
import com.gvchat.common.media.config.MediaProperties;
import com.gvchat.common.media.config.SupportPersistenceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.gvchat.common.media")
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
