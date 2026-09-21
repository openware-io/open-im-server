package com.gvchat.im.user.config;

import com.gvchat.infrastructure.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * OAuth 开放平台安全配置：
 * <ul>
 *   <li>/oauth/**：授权运行时端点（authorize/token/userinfo）公开，授权码校验、PKCE 与令牌解析在领域服务内完成；</li>
 *   <li>/open/**：第三方接入公开端点（提交申请、查询审核状态）公开，不返回 appSecret；</li>
 *   <li>审核管理（批准/驳回/重置密钥/吊销）位于 /internal/admin/open-applications（HMAC 内部端点，仅供 im-admin-service）。</li>
 * </ul>
 * 仍挂载 JwtAuthenticationFilter，使 /oauth/authorize 能解析登录态 JWT 拿到当前用户上下文。
 */
@Configuration
public class OauthSecurityConfig {
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain oauthSecurityFilterChain(
      HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
    http.securityMatcher("/oauth/**", "/open/**", "/.well-known/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
