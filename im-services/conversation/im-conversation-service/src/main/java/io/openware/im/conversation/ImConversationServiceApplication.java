package io.openware.im.conversation;

import io.openware.im.conversation.config.ConversationPersistenceConfig;
import io.openware.im.conversation.config.RtcProperties;
import io.openware.im.conversation.infra.integration.admin.AdminServiceProperties;
import io.openware.im.conversation.infra.integration.user.UserServiceProperties;
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "io.openware.im.conversation")
@EnableConfigurationProperties({RtcProperties.class, UserServiceProperties.class, AdminServiceProperties.class})
@Import({
    ConversationPersistenceConfig.class,
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
public class ImConversationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ImConversationServiceApplication.class, args);
  }
}
