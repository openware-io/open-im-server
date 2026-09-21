package com.gvchat.im.user.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.im.user.api.devicekey.RegisterDeviceKeyRequest;
import com.gvchat.im.user.application.devicekey.DeviceKeyApplicationService;
import com.gvchat.im.user.application.devicekey.command.RegisterDeviceKeyCommand;
import com.gvchat.im.user.application.devicekey.result.DeviceKeyResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "设备密钥")
@RequestMapping("/device-keys")
@RequiredArgsConstructor
public class DeviceKeyController {
  private final DeviceKeyApplicationService deviceKeyService;

  @PostMapping
  public DeviceKeyResult register(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody RegisterDeviceKeyRequest dto) {
    return deviceKeyService.register(user.getId(), new RegisterDeviceKeyCommand(dto.getDeviceId(), dto.getPublicKey()));
  }

  @GetMapping("/me")
  public List<DeviceKeyResult> getMyDeviceKeys(@AuthenticationPrincipal SecurityUser user) {
    return deviceKeyService.getMyDeviceKeys(user.getId());
  }
}
