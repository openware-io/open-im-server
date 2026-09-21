package com.gvchat.im.accessws;

import com.gvchat.common.util.SnowflakeIdGenerator;
import com.gvchat.infrastructure.config.RedisConfig;
import com.gvchat.infrastructure.mq.MqInfrastructureConfig;
import com.gvchat.infrastructure.security.JwtProperties;
import com.gvchat.infrastructure.security.JwtTokenProvider;
import com.gvchat.infrastructure.security.AuthenticationUserProjection;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({
    RedisConfig.class,
    MqInfrastructureConfig.class,
    JwtProperties.class,
    JwtTokenProvider.class,
    AuthenticationUserProjection.class,
    InternalServiceAuthenticationProperties.class,
    InternalServiceAuthentication.class,
    InternalServiceAuthenticationInterceptor.class,
    SnowflakeIdGenerator.class
})
public class ImAccessWsApplication {
  public static void main(String[] args) {
    SpringApplication.run(ImAccessWsApplication.class, args);
  }
}
