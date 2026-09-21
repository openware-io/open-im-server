package com.gvchat.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全配置。
 *
 * <p>该配置采用无状态 JWT 认证方案：
 * <ul>
 *   <li>关闭 Session（STATELESS），每个请求都通过 {@link JwtAuthenticationFilter} 尝试建立认证上下文。</li>
 *   <li>对公开接口放行，对管理端接口施加 ADMIN 角色约束，其余接口默认要求已认证。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final InternalServiceAuthenticationFilter internalServiceAuthenticationFilter;
  private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

  /**
   * 注入 JWT 认证过滤器与统一认证失败处理器。
   *
   * @param jwtAuthenticationFilter 解析 Bearer Token 并写入 SecurityContext 的过滤器
   */
  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      InternalServiceAuthenticationFilter internalServiceAuthenticationFilter,
      RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.internalServiceAuthenticationFilter = internalServiceAuthenticationFilter;
    this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
  }

  /**
   * 配置 HTTP 安全过滤链：禁用 CSRF、启用无状态会话策略、按路径声明授权规则，并注册 JWT 过滤器顺序。
   *
   * @param http Spring Security 构建器
   * @return 构建完成的安全过滤链
   * @throws Exception 配置过程中可能抛出的异常
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    // 禁用 CSRF，并启用无状态会话
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // 按路径配置公开接口、管理员角色与默认鉴权策略
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/auth/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/account-cancellations").authenticated()
            .requestMatchers(HttpMethod.GET, "/account-cancellations/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/client/release-check").permitAll()
            .requestMatchers(HttpMethod.GET, "/client/releases/latest").permitAll()
            .requestMatchers("/config/client/**").permitAll()
            .requestMatchers("/miniapp/**").permitAll()
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/api-docs/**").permitAll()
            .requestMatchers("/ws/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
            .requestMatchers("/internal/admin/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/conversation/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/user/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/media/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/channels/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/secret-chats/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/secret-group-chats/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/internal/iam/**").hasAuthority("ROLE_INTERNAL_ADMIN")
            .requestMatchers("/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated())
        .exceptionHandling(e -> e.authenticationEntryPoint(restAuthenticationEntryPoint))
        // JWT 过滤器应置于用户名密码过滤器之前
        .addFilterBefore(internalServiceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(jwtAuthenticationFilter, InternalServiceAuthenticationFilter.class);
    return http.build();
  }

  /**
   * 注册 BCrypt 密码编码器 Bean。
   *
   * @return BCrypt 实现的 {@link PasswordEncoder}
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
