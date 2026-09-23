package io.openware.im.user.api.controller;

import io.openware.im.user.api.dto.request.RegisterOpenApplicationRequest;
import io.openware.im.user.api.dto.response.OpenApplicationRegistrationResponse;
import io.openware.im.user.api.dto.response.OpenApplicationResponse;
import io.openware.im.user.application.openplatform.OpenPlatformApplicationService;
import io.openware.im.user.application.openplatform.command.RegisterApplicationCommand;
import io.openware.im.user.application.openplatform.result.ApplicationResult;
import io.openware.im.user.application.openplatform.result.RegisteredApplicationResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放平台第三方接入（公开）：第三方提交接入申请 + 查询审核状态。
 * 申请进入 PENDING，密钥在审核通过后分配；本控制器不暴露密钥（审核由内部管理端点处理）。
 */
@RestController
@Tag(name = "开放平台")
@RequestMapping("/open")
@RequiredArgsConstructor
public class OpenApplicationController {
  private final OpenPlatformApplicationService openPlatformApplicationService;

  /** 第三方提交接入申请：返回 appId 与待审核状态，不返回 appSecret。 */
  @PostMapping("/applications")
  public OpenApplicationRegistrationResponse register(@Valid @RequestBody RegisterOpenApplicationRequest request) {
    RegisteredApplicationResult result = openPlatformApplicationService.registerApplication(
        new RegisterApplicationCommand(request.getAppName(), request.getSubjectName(), request.getAppType(),
            request.getCallbackUrl(), request.getScopes()));
    return new OpenApplicationRegistrationResponse(result.appId(), result.appSecret(), result.appName(), result.status());
  }

  /** 第三方查询申请状态（含驳回原因），不返回 appSecret。 */
  @GetMapping("/applications/{appId}")
  public OpenApplicationResponse get(@PathVariable String appId) {
    ApplicationResult result = openPlatformApplicationService.getApplication(appId);
    return new OpenApplicationResponse(result.appId(), result.appName(), result.appType(), result.callbackUrl(),
        result.scopes(), result.status(), result.rejectReason());
  }
}
