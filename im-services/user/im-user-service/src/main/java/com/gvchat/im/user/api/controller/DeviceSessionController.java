package com.gvchat.im.user.api.controller;

import com.gvchat.im.user.api.converter.DeviceSessionApiConverter;
import com.gvchat.im.user.api.dto.response.DeviceSessionResponse;
import com.gvchat.im.user.application.device.DeviceSessionApplicationService;
import com.gvchat.infrastructure.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 多端登录设备管理：查询当前账号设备会话列表，以及踢出/退出设备。 */
@RestController
@Tag(name = "登录设备管理")
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceSessionController {
  private final DeviceSessionApplicationService deviceSessionApplicationService;

  @GetMapping
  @Operation(summary = "查询当前账号设备会话列表（登录 IP / 方式 / 设备 / 最后活跃时间）")
  public List<DeviceSessionResponse> list(@AuthenticationPrincipal SecurityUser user) {
    return deviceSessionApplicationService.listDevices(user.getId()).stream()
        .map(DeviceSessionApiConverter::toResponse).toList();
  }

  @PostMapping("/{deviceId}/kick")
  @Operation(summary = "主设备踢出指定设备")
  public Map<String, Boolean> kick(@AuthenticationPrincipal SecurityUser user, @PathVariable String deviceId) {
    deviceSessionApplicationService.kick(user.getId(), deviceId);
    return Map.of("ok", true);
  }

  @PostMapping("/{deviceId}/logout")
  @Operation(summary = "副设备主动退出登录")
  public Map<String, Boolean> logout(@AuthenticationPrincipal SecurityUser user, @PathVariable String deviceId) {
    deviceSessionApplicationService.logout(user.getId(), deviceId);
    return Map.of("ok", true);
  }
}
