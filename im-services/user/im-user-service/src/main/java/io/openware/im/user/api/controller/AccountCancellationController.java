package io.openware.im.user.api.controller;

import io.openware.im.user.api.ClientIpUtil;
import io.openware.im.user.api.dto.request.SubmitAccountCancellationRequest;
import io.openware.im.user.api.dto.response.AccountCancellationStatusResponse;
import io.openware.im.user.api.dto.response.AccountCancellationStepResponse;
import io.openware.im.user.api.dto.response.AccountCancellationSubmitResponse;
import io.openware.im.user.application.cancellation.AccountCancellationApplicationService;
import io.openware.im.user.application.cancellation.command.SubmitAccountCancellationCommand;
import io.openware.im.user.application.cancellation.result.AccountCancellationStatusResult;
import io.openware.im.user.application.cancellation.result.AccountCancellationSubmitResult;
import io.openware.infrastructure.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账号注销申请接口：提交需认证通过；状态轮询凭一次性令牌（账号删除后登录态失效仍可查询）。 */
@RestController
@Tag(name = "账号注销申请")
@RequestMapping("/account-cancellations")
@RequiredArgsConstructor
public class AccountCancellationController {
  private final AccountCancellationApplicationService applicationService;

  @PostMapping
  @Operation(summary = "提交账号注销申请（需认证）")
  public AccountCancellationSubmitResponse submit(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody SubmitAccountCancellationRequest request, HttpServletRequest servletRequest) {
    String source = request.getSource() == null || request.getSource().isBlank()
        ? "web" : request.getSource().trim().toLowerCase();
    AccountCancellationSubmitResult result = applicationService.submit(new SubmitAccountCancellationCommand(
        user.getId(), request.getPassword(), source, ClientIpUtil.resolve(servletRequest)));
    return new AccountCancellationSubmitResponse(result.applicationId(), result.status(), result.statusToken());
  }

  @GetMapping("/{id}")
  @Operation(summary = "查询注销申请状态（凭一次性令牌）")
  public AccountCancellationStatusResponse status(@PathVariable Long id, @RequestParam String token) {
    return toStatusResponse(applicationService.getStatus(id, token));
  }

  private AccountCancellationStatusResponse toStatusResponse(AccountCancellationStatusResult result) {
    return new AccountCancellationStatusResponse(
        result.applicationId(), result.status(),
        result.steps().stream()
            .map(step -> new AccountCancellationStepResponse(step.code(), step.completed()))
            .toList(),
        result.requestedAt(), result.completedAt(), result.failureReason());
  }
}
