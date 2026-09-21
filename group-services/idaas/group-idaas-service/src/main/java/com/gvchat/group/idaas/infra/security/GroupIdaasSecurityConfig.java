package com.gvchat.group.idaas.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * IDaaS 安全配置：仅提供 BCrypt 密码编码器，不启用 Web Security（登录接口对外公开）。
 */
@Configuration
public class GroupIdaasSecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
