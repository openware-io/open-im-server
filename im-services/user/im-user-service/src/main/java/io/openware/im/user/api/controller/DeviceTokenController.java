package io.openware.im.user.api.controller;

import io.openware.infrastructure.security.SecurityUser;
import io.openware.im.user.api.converter.DeviceTokenApiConverter;
import io.openware.im.user.api.dto.request.RegisterDeviceTokenRequest;
import io.openware.im.user.api.dto.request.RemoveDeviceTokenRequest;
import io.openware.im.user.api.dto.response.DeviceTokenResponse;
import io.openware.im.user.application.device.DeviceTokenApplicationService;
import io.openware.im.user.application.device.command.RegisterDeviceTokenCommand;
import io.openware.im.user.application.device.command.RemoveDeviceTokenCommand;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "推送设备")
@RequestMapping("/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {
  private final DeviceTokenApplicationService deviceTokenApplicationService;

  @PostMapping
  public DeviceTokenResponse register(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody RegisterDeviceTokenRequest request) {
    return DeviceTokenApiConverter.toResponse(deviceTokenApplicationService.register(user.getId(),
        new RegisterDeviceTokenCommand(request.getToken(), request.getPlatform(), request.getPushProvider(),
            request.getDeviceId())));
  }

  @DeleteMapping
  public Map<String, Boolean> remove(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody RemoveDeviceTokenRequest request) {
    deviceTokenApplicationService.remove(user.getId(),
        new RemoveDeviceTokenCommand(request.getToken(), request.getPushProvider()));
    return Map.of("ok", true);
  }
}
