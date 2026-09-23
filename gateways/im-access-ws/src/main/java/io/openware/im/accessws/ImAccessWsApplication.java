package io.openware.im.accessws;

import io.openware.common.util.SnowflakeIdGenerator;
import io.openware.infrastructure.config.RedisConfig;
import io.openware.infrastructure.mq.MqInfrastructureConfig;
import io.openware.infrastructure.security.JwtProperties;
import io.openware.infrastructure.security.JwtTokenProvider;
import io.openware.infrastructure.security.AuthenticationUserProjection;
import io.openware.infrastructure.security.InternalServiceAuthentication;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.infrastructure.security.InternalServiceAuthenticationProperties;
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
