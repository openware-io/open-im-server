package io.openware.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Restricts the API documentation UI and every aggregated OpenAPI specification to a dedicated account. */
@Configuration
@EnableWebFluxSecurity
public class ApiDocumentationSecurityConfig {
  private static final String DOCUMENTATION_ROLE = "API_DOCUMENTATION";

  @Bean
  public PasswordEncoder documentationPasswordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public MapReactiveUserDetailsService documentationUsers(
      ApiDocumentationProperties properties, PasswordEncoder documentationPasswordEncoder) {
    properties.validate();
    UserDetails user = User.withUsername(properties.getUsername())
        .password(documentationPasswordEncoder.encode(properties.getPassword()))
        .roles(DOCUMENTATION_ROLE)
        .build();
    return new MapReactiveUserDetailsService(user);
  }

  @Bean
  public SecurityWebFilterChain documentationSecurityWebFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(Customizer.withDefaults())
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .authorizeExchange(exchange -> exchange
            .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/_docs/**")
            .hasRole(DOCUMENTATION_ROLE)
            .pathMatchers("/actuator/health", "/actuator/info")
            .permitAll()
            .anyExchange()
            .permitAll())
        .build();
  }
}
