package io.openware.im.admin.api;

import io.openware.common.dto.PageResult;
import io.openware.im.admin.integration.OpenPlatformAdminClient;
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
 * 开放平台第三方接入管理（IM 管理后台，ROLE_ADMIN）：
 * 列表/详情/审核（批准、驳回）/重置密钥/吊销。批准与重置返回一次性明文 appSecret。
 */
@RestController
@RequestMapping("/admin/open-platform")
@RequiredArgsConstructor
public class OpenPlatformAdminController {
  private final OpenPlatformAdminClient client;

  @GetMapping("/applications")
  public PageResult<Map<String, Object>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String appType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return client.listApplications(status, appType, page, pageSize);
  }

  @GetMapping("/applications/{appId}")
  public Map<String, Object> detail(@PathVariable String appId) {
    return client.detail(appId);
  }

  @PostMapping("/applications/{appId}/approve")
  public Map<String, Object> approve(@PathVariable String appId) {
    return client.approve(appId);
  }

  @PostMapping("/applications/{appId}/reject")
  public Map<String, Object> reject(@PathVariable String appId, @RequestBody Map<String, String> body) {
    return client.reject(appId, body == null ? null : body.get("reason"));
  }

  @PostMapping("/applications/{appId}/reset-secret")
  public Map<String, Object> resetSecret(@PathVariable String appId) {
    return client.resetSecret(appId);
  }

  @PostMapping("/applications/{appId}/revoke")
  public void revoke(@PathVariable String appId) {
    client.revoke(appId);
  }
}
