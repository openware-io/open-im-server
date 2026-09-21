package com.gvchat.im.message.api.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.im.message.api.converter.FavoriteApiConverter;
import com.gvchat.im.message.api.dto.request.AddFavoriteRequest;
import com.gvchat.im.message.api.dto.request.AddFavoritesBatchRequest;
import com.gvchat.im.message.api.dto.response.FavoriteBatchResponse;
import com.gvchat.im.message.api.dto.response.FavoritePageResponse;
import com.gvchat.im.message.api.dto.response.FavoriteSourceResponse;
import com.gvchat.im.message.api.dto.response.OperationResponse;
import com.gvchat.im.message.application.favorite.FavoriteApplicationService;
import com.gvchat.im.message.application.favorite.command.AddFavoriteCommand;
import com.gvchat.im.message.application.favorite.command.AddFavoritesBatchCommand;
import com.gvchat.im.message.application.favorite.query.FavoriteListQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "消息收藏")
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class FavoriteController {
  private final FavoriteApplicationService favoriteApplicationService;

  @PostMapping({"", "/"})
  @Operation(summary = "收藏一条消息（幂等）")
  public OperationResponse favorite(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody AddFavoriteRequest request) {
    favoriteApplicationService.favorite(new AddFavoriteCommand(user.getId(), request.msgId(), request.peerId(),
        request.chatType()));
    return new OperationResponse(true);
  }

  @PostMapping("/batch")
  @Operation(summary = "批量收藏同一会话的多条消息（逐条幂等，单条失败不影响其余）")
  public FavoriteBatchResponse favoriteBatch(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody AddFavoritesBatchRequest request) {
    return FavoriteApiConverter.toResponse(favoriteApplicationService.favoriteBatch(
        new AddFavoritesBatchCommand(user.getId(), request.peerId(), request.chatType(), request.messageIds())));
  }

  @DeleteMapping("/{msgId}")
  @Operation(summary = "取消收藏（幂等）")
  public OperationResponse unfavorite(@AuthenticationPrincipal SecurityUser user, @PathVariable String msgId) {
    favoriteApplicationService.unfavorite(user.getId(), msgId);
    return new OperationResponse(true);
  }

  @GetMapping({"", "/"})
  @Operation(summary = "分页查询我的收藏")
  public FavoritePageResponse list(@AuthenticationPrincipal SecurityUser user,
      @RequestParam(defaultValue = "1") Integer page, @RequestParam(defaultValue = "20") Integer pageSize) {
    return FavoriteApiConverter.toResponse(favoriteApplicationService.listFavorites(
        new FavoriteListQuery(user.getId(), page, pageSize)));
  }

  @GetMapping("/{msgId}/source")
  @Operation(summary = "查询收藏对应原消息是否仍可跳转（查询失败一律 fail closed）")
  public FavoriteSourceResponse source(@AuthenticationPrincipal SecurityUser user, @PathVariable String msgId) {
    return FavoriteApiConverter.toResponse(favoriteApplicationService.locateSource(user.getId(), msgId));
  }
}
