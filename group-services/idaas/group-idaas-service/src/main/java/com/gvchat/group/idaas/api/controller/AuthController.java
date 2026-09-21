package com.gvchat.group.idaas.api.controller;

import com.gvchat.group.idaas.api.dto.AuthDtos.LoginRequest;
import com.gvchat.group.idaas.api.dto.AuthDtos.LoginResponse;
import com.gvchat.group.idaas.api.dto.AuthDtos.SessionResponse;
import com.gvchat.group.idaas.api.dto.AuthDtos.SsoTicketResponse;
import com.gvchat.group.idaas.api.dto.AuthDtos.SsoVerifyRequest;
import com.gvchat.group.idaas.api.dto.AuthDtos.SsoVerifyResponse;
import com.gvchat.group.idaas.application.AuthApplicationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证域接口：登录态存 Redis，浏览器仅持有 HttpOnly Cookie（idaas_session）。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private static final String COOKIE_NAME = "idaas_session";

  private final AuthApplicationService authApplicationService;

  public AuthController(AuthApplicationService authApplicationService) {
    this.authApplicationService = authApplicationService;
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    LoginResponse result = authApplicationService.login(request);
    long remaining = Math.max(1, result.expiresAt() - System.currentTimeMillis());
    addSessionCookie(response, result.sessionId(), Duration.ofMillis(remaining));
    return result;
  }

  @GetMapping("/session")
  public SessionResponse session(@CookieValue(value = COOKIE_NAME, required = false) String sessionId) {
    return authApplicationService.currentSession(sessionId);
  }

  @PostMapping("/logout")
  public void logout(@CookieValue(value = COOKIE_NAME, required = false) String sessionId,
                     HttpServletResponse response) {
    authApplicationService.logout(sessionId);
    clearSessionCookie(response);
  }

  @PostMapping("/sso-ticket")
  public SsoTicketResponse ssoTicket(@CookieValue(value = COOKIE_NAME, required = false) String sessionId) {
    return authApplicationService.issueTicket(sessionId);
  }

  @PostMapping("/sso/verify")
  public SsoVerifyResponse verifySso(@Valid @RequestBody SsoVerifyRequest request) {
    return authApplicationService.verifySso(request.ticket());
  }

  private void addSessionCookie(HttpServletResponse response, String sessionId, Duration ttl) {
    ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, sessionId)
        .httpOnly(true).secure(true).sameSite("None").path("/")
        .maxAge(ttl).build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private void clearSessionCookie(HttpServletResponse response) {
    ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
        .httpOnly(true).secure(true).sameSite("None").path("/")
        .maxAge(Duration.ZERO).build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
