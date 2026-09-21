package com.gvchat.im.user.api.controller.internal;

import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.application.openplatform.OpenPlatformApplicationService;
import com.gvchat.im.user.application.openplatform.result.ApplicationResult;
import com.gvchat.im.user.application.openplatform.result.RegisteredApplicationResult;
import com.gvchat.im.user.domain.openplatform.model.OpenApplicationStatus;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放平台第三方接入内部管理端点（HMAC 保护，供 im-admin-service 调用）：
 * 审核（批准/驳回）、列表、详情、重置密钥、吊销。批准/重置时返回一次性明文 appSecret。
 */
@RestController
@RequestMapping("/internal/admin/open-applications")
@RequiredArgsConstructor
public class InternalOpenPlatformAdminController {
  private final OpenPlatformApplicationService service;

  @GetMapping
  public PageResult<ApplicationResult> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String appType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return service.listApplications(parseStatus(status), appType, page, pageSize);
  }

  @GetMapping("/{appId}")
  public ApplicationResult detail(@PathVariable String appId) {
    return service.getApplication(appId);
  }

  @PostMapping("/{appId}/approve")
  public RegisteredApplicationResult approve(@PathVariable String appId) {
    return service.approveApplication(appId, 0L);
  }

  @PostMapping("/{appId}/reject")
  public ApplicationResult reject(@PathVariable String appId, @RequestBody RejectRequest request) {
    return service.rejectApplication(appId, request.reason(), 0L);
  }

  @PostMapping("/{appId}/reset-secret")
  public RegisteredApplicationResult resetSecret(@PathVariable String appId) {
    return service.resetSecret(appId);
  }

  @PostMapping("/{appId}/revoke")
  public Map<String, Boolean> revoke(@PathVariable String appId) {
    service.revokeApplication(appId);
    return Map.of("ok", true);
  }

  private OpenApplicationStatus parseStatus(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OpenApplicationStatus.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public record RejectRequest(String reason) {
  }
}
