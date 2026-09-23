package io.openware.im.admin.controller;

import java.util.List;
import io.openware.common.dto.PageResult;
import io.openware.im.admin.application.query.AdminCrossDomainQueryService;
import io.openware.im.user.api.admin.AdminDeviceTokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 */
@RestController
@RequestMapping("/admin/device-tokens")
@RequiredArgsConstructor
public class AdminDeviceTokenController {
  private final AdminCrossDomainQueryService queryService;

/**
 */
  @GetMapping("/{userId}")
  public List<AdminDeviceTokenResponse> listByUser(@PathVariable Long userId) {
    return queryService.listDeviceTokens(userId);
  }

  @GetMapping
  public PageResult<AdminDeviceTokenResponse> list(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int pageSize,
      @org.springframework.web.bind.annotation.RequestParam(required = false) Long userId) {
    return queryService.listAllDeviceTokens(page, pageSize, userId);
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
  public void delete(@PathVariable Long id) {
    queryService.disableDeviceToken(id);
  }
}

