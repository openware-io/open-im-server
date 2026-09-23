package io.openware.im.user.api.controller;

import io.openware.im.user.api.ClientIpUtil;
import io.openware.im.user.api.converter.UserAccountApiConverter;
import io.openware.im.user.api.dto.request.ForgotPasswordBySmsRequest;
import io.openware.im.user.api.dto.request.ForgotPasswordRequest;
import io.openware.im.user.api.dto.request.LoginRequest;
import io.openware.im.user.api.dto.request.RegisterRequest;
import io.openware.im.user.api.dto.request.ResetPasswordBySecurityQuestionRequest;
import io.openware.im.user.api.dto.request.ResetPasswordBySmsRequest;
import io.openware.im.user.api.dto.request.ResetPasswordRequest;
import io.openware.im.user.api.dto.request.SetSecurityQuestionRequest;
import io.openware.im.user.api.dto.request.SsoLoginRequest;
import io.openware.im.user.api.dto.response.TokenResponse;
import io.openware.im.user.application.account.AccountApplicationService;
import io.openware.im.user.application.account.PasswordRecoveryApplicationService;
import io.openware.im.user.application.account.command.LoginCommand;
import io.openware.im.user.application.account.command.RegisterAccountCommand;
import io.openware.im.user.application.websocket.WebSocketTicketService;
import io.openware.infrastructure.security.SecurityUser;
import java.time.Instant;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@Tag(name = "身份认证")
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AccountApplicationService accountApplicationService;
  private final PasswordRecoveryApplicationService passwordRecoveryApplicationService;
  private final WebSocketTicketService webSocketTicketService;

  @PostMapping("/register")
  @Operation(summary = "用户注册")
  public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
    return UserAccountApiConverter.toTokenResponse(accountApplicationService.register(
        new RegisterAccountCommand(
            request.getUsername(), request.getPassword(), request.getNickname(), request.getEmail(),
            request.getPhone())));
  }

  @PostMapping("/login")
  @Operation(summary = "账号登录")
  public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
    return UserAccountApiConverter.toTokenResponse(accountApplicationService.login(
        new LoginCommand(request.getUsername(), request.getPassword(), request.getDeviceId(), request.getDeviceType(),
            request.getDeviceName(), ClientIpUtil.resolve(servletRequest))));
  }

  @PostMapping("/sso")
  @Operation(summary = "IDaaS SSO 免二次登录（校验一次性票据并签发 IM 后台 Token）")
  public TokenResponse ssoLogin(@Valid @RequestBody SsoLoginRequest request, HttpServletRequest servletRequest) {
    return UserAccountApiConverter.toTokenResponse(
        accountApplicationService.ssoLogin(request.ticket(), ClientIpUtil.resolve(servletRequest)));
  }

  @PostMapping("/password/forgot")
  @Operation(summary = "邮箱找回密码（发送重置邮件）")
  public Map<String, Boolean> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
    passwordRecoveryApplicationService.requestReset(request.getEmail());
    return Map.of("ok", true);
  }

  @PostMapping("/password/forgot-sms")
  @Operation(summary = "手机验证码找回密码（下发短信验证码）")
  public Map<String, Boolean> forgotPasswordBySms(@Valid @RequestBody ForgotPasswordBySmsRequest request) {
    passwordRecoveryApplicationService.requestResetBySms(request.getPhone());
    return Map.of("ok", true);
  }

  @PostMapping("/password/reset-by-sms")
  @Operation(summary = "凭手机验证码设置新密码")
  public Map<String, Boolean> resetPasswordBySms(@Valid @RequestBody ResetPasswordBySmsRequest request) {
    passwordRecoveryApplicationService.resetPasswordBySms(request.getPhone(), request.getCode(),
        request.getNewPassword());
    return Map.of("ok", true);
  }

  @PostMapping("/password/security-question")
  @Operation(summary = "设置（或更新）密保问题（需认证）")
  public Map<String, Boolean> setSecurityQuestion(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody SetSecurityQuestionRequest request) {
    passwordRecoveryApplicationService.setSecurityQuestion(user.getId(), request.getQuestion(), request.getAnswer());
    return Map.of("ok", true);
  }

  @PostMapping("/password/reset-by-security-question")
  @Operation(summary = "密保问题找回密码")
  public Map<String, Boolean> resetPasswordBySecurityQuestion(
      @Valid @RequestBody ResetPasswordBySecurityQuestionRequest request) {
    passwordRecoveryApplicationService.resetPasswordBySecurityQuestion(request.getUsername(), request.getQuestion(),
        request.getAnswer(), request.getNewPassword());
    return Map.of("ok", true);
  }

  @PostMapping("/password/reset")
  @Operation(summary = "凭重置令牌设置新密码")
  public Map<String, Boolean> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    passwordRecoveryApplicationService.resetPassword(request.getToken(), request.getNewPassword());
    return Map.of("ok", true);
  }

  @PostMapping("/ws-ticket")
  @Operation(summary = "Sign a one-time WebSocket ticket")
  public WebSocketTicketResponse createWebSocketTicket(@AuthenticationPrincipal SecurityUser user) {
    if (user == null || !user.isEnabled()) {
      throw new org.springframework.security.access.AccessDeniedException("Authentication required");
    }
    WebSocketTicketService.IssuedTicket ticket = webSocketTicketService.issue(user.getId());
    return new WebSocketTicketResponse(ticket.ticket(), ticket.expiresAt());
  }

  public record WebSocketTicketResponse(String ticket, Instant expiresAt) { }
}
