package com.gvchat.im.conversation;

import com.gvchat.im.conversation.config.ConversationPersistenceConfig;
import com.gvchat.im.conversation.config.RtcProperties;
import com.gvchat.im.conversation.infra.integration.admin.AdminServiceProperties;
import com.gvchat.im.conversation.infra.integration.user.UserServiceProperties;
import com.gvchat.infrastructure.config.RedisConfig;
import com.gvchat.infrastructure.mq.MqInfrastructureConfig;
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.gvchat.im.conversation")
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
