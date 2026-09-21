package com.gvchat.infrastructure.security;

import com.gvchat.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：从 Authorization 请求头解析 Bearer Token，并在认证成功后写入 SecurityContext。
 *
 * <p>设计原则：
 * <ul>
 *   <li>过滤器每个请求最多执行一次（OncePerRequestFilter）。</li>
 *   <li>该过滤器只负责“尝试建立认证上下文”，不负责决定是否放行。</li>
 *   <li>当 Token 无效/过期、或用户不存在/非 ACTIVE 时，会清空上下文，并在 request 上写入 {@code jwt_error} 标记，便于后续链路做统一审计与诊断。</li>
 * </ul>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtTokenProvider jwtTokenProvider;
  private final AuthenticationUserProjection authenticationUserProjection;

  /**
   * 注入 JWT 解析器与用户仓储。
   *
   * @param jwtTokenProvider JWT 令牌提供方
   * @param userRepository  用户数据访问
   */
  public JwtAuthenticationFilter(
      JwtTokenProvider jwtTokenProvider,
      AuthenticationUserProjection authenticationUserProjection) {
    this.jwtTokenProvider = jwtTokenProvider;
    this.authenticationUserProjection = authenticationUserProjection;
  }

  /**
   * 解析 Bearer Token，验证用户状态后写入 SecurityContext，并继续过滤链。
   *
   * @param request HTTP 请求
   * @param response HTTP 响应
   * @param chain  后续过滤器链
   * @throws ServletException Servlet 处理异常
   * @throws IOException   IO 异常
   */
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null) {
      header = header.trim();
    }
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      // 步骤 1：剥离 "Bearer " 前缀，提取 JWT
      String token = header.substring(7).trim();
      // OAuth /userinfo 使用随机不透明 access_token，不应交给 JWT 解析器。
      // 仅对 JWT compact serialization（header.payload.signature）执行解析，
      // 避免公共 OAuth 端点被无关的 JWT 解析告警/认证处理干扰。
      if (token.chars().filter(ch -> ch == '.').count() != 2) {
        chain.doFilter(request, response);
        return;
      }
      try {
        // 步骤 2：解析 Claims，从 subject 获取用户 ID
        Claims claims = jwtTokenProvider.parseClaims(token);
        Long userId = Long.parseLong(claims.getSubject());
        Number authenticationVersion = claims.get("authentication_version", Number.class);
        if (authenticationVersion == null) {
          throw new IllegalArgumentException("JWT authentication version is missing");
        }
        var snapshot = authenticationUserProjection.findByUserId(userId);
        if (snapshot.isEmpty()) {
          request.setAttribute("jwt_error", "USER_NOT_FOUND");
        } else if (!snapshot.get().active()) {
          request.setAttribute("jwt_error", "USER_INACTIVE");
        } else if (snapshot.get().authenticationVersion() != authenticationVersion.longValue()) {
          request.setAttribute("jwt_error", "TOKEN_REVOKED");
        } else {
          // 步骤 3：仅活跃用户才建立认证上下文
          SecurityUser principal = new SecurityUser(
              snapshot.get().userId(), snapshot.get().username(), "",
              snapshot.get().role() != null ? snapshot.get().role() : UserRole.USER, true);
          var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
          SecurityContextHolder.getContext().setAuthentication(auth);
        }
      } catch (ExpiredJwtException e) {
        request.setAttribute("jwt_error", "TOKEN_EXPIRED");
        SecurityContextHolder.clearContext();
        log.warn("JWT token expired, method={}, uri={}", request.getMethod(), request.getRequestURI(), e);
      } catch (JwtException | IllegalArgumentException e) {
        request.setAttribute("jwt_error", "TOKEN_INVALID");
        SecurityContextHolder.clearContext();
        log.warn("JWT token is invalid, method={}, uri={}", request.getMethod(), request.getRequestURI(), e);
      } catch (Exception e) {
        request.setAttribute("jwt_error", "AUTH_ERROR");
        SecurityContextHolder.clearContext();
        log.error(
            "Unexpected JWT authentication error, method={}, uri={}",
            request.getMethod(),
            request.getRequestURI(),
            e);
      }
    }
    chain.doFilter(request, response);
  }
}
