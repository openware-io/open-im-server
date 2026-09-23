package io.openware.im.user.api.controller;

import io.openware.im.user.api.ClientIpUtil;
import io.openware.im.user.api.dto.request.QrLoginConfirmRequest;
import io.openware.im.user.api.dto.response.AuthUserResponse;
import io.openware.im.user.api.dto.response.QrLoginPollResponse;
import io.openware.im.user.api.dto.response.QrLoginSessionResponse;
import io.openware.im.user.application.account.AccountApplicationService;
import io.openware.im.user.application.account.result.AuthenticatedAccountResult;
import io.openware.im.user.application.websocket.QrLoginService;
import io.openware.infrastructure.security.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 扫码登录：桌面端展示二维码，手机 App 扫码确认。 */
@RestController
@Tag(name = "扫码登录")
@RequestMapping("/auth")
@RequiredArgsConstructor
public class QrLoginController {
  private static final Duration SESSION_TTL = Duration.ofSeconds(120);

  private final QrLoginService qrLoginService;
  private final AccountApplicationService accountApplicationService;

  @PostMapping("/qr-login/session")
  @Operation(summary = "创建扫码登录会话，返回二维码 token")
  public QrLoginSessionResponse createSession() {
    String qrToken = qrLoginService.createSession();
    return new QrLoginSessionResponse(qrToken, Instant.now().plus(SESSION_TTL));
  }

  @PostMapping("/qr-login/confirm")
  @Operation(summary = "手机端扫码后确认登录")
  public Map<String, Boolean> confirm(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody QrLoginConfirmRequest request) {
    if (user == null || !user.isEnabled()) {
      throw new AccessDeniedException("Authentication required");
    }
    boolean ok = qrLoginService.confirm(request.qrToken(), user.getId());
    return Map.of("ok", ok);
  }

  @GetMapping("/qr-login/session/{qrToken}")
  @Operation(summary = "桌面端轮询扫码登录状态，确认后一次性返回 token")
  public QrLoginPollResponse poll(@PathVariable String qrToken, HttpServletRequest servletRequest) {
    QrLoginService.QrLoginState state = qrLoginService.poll(qrToken);
    if ("confirmed".equals(state.status()) && state.userId() != null) {
      AuthenticatedAccountResult result = accountApplicationService.loginByQr(state.userId(), ClientIpUtil.resolve(servletRequest));
      return new QrLoginPollResponse("confirmed", result.accessToken(),
          new AuthUserResponse(result.id(), result.username(), result.nickname(), result.avatar()));
    }
    return new QrLoginPollResponse(state.status(), null, null);
  }
}
