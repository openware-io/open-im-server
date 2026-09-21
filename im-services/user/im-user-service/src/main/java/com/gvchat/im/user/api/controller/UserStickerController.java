package com.gvchat.im.user.api.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.im.user.api.converter.UserStickerApiConverter;
import com.gvchat.im.user.api.dto.request.AddUserStickerRequest;
import com.gvchat.im.user.api.dto.request.BatchAddUserStickerRequest;
import com.gvchat.im.user.api.dto.response.UserStickerResponse;
import com.gvchat.im.user.application.sticker.StickerApplicationService;
import com.gvchat.im.user.application.sticker.command.AddUserStickerCommand;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "我的贴纸")
@RequestMapping("/user-stickers")
@RequiredArgsConstructor
public class UserStickerController {
  private final StickerApplicationService stickerApplicationService;

  @GetMapping
  public List<UserStickerResponse> list(@AuthenticationPrincipal SecurityUser user) {
    return stickerApplicationService.listByUser(user.getId()).stream()
        .map(UserStickerApiConverter::toResponse)
        .toList();
  }

  @PostMapping
  public UserStickerResponse add(
      @AuthenticationPrincipal SecurityUser user, @Valid @RequestBody AddUserStickerRequest request) {
    return UserStickerApiConverter.toResponse(stickerApplicationService.add(
        user.getId(), new AddUserStickerCommand(request.getUrl(), request.getThumbnail())));
  }

  @PostMapping("/batch")
  public List<UserStickerResponse> batchAdd(
      @AuthenticationPrincipal SecurityUser user, @Valid @RequestBody BatchAddUserStickerRequest request) {
    return stickerApplicationService.batchAdd(user.getId(), request.getUrls().stream()
            .map(url -> new AddUserStickerCommand(url, null))
            .toList())
        .stream().map(UserStickerApiConverter::toResponse).toList();
  }

  @DeleteMapping("/{id}")
  public void remove(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    stickerApplicationService.remove(user.getId(), id);
  }
}
