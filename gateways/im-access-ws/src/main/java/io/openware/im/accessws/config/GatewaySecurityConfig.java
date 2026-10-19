package io.openware.im.accessws.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class GatewaySecurityConfig {
  @Bean
  public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/ws/**").permitAll()
            .anyRequest().denyAll());
    return http.build();
  }

  /** Suppress Spring Boot's generated default user (and its startup password log). */
  @Bean
  public UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("Password login is not supported");
    };
  }
}
