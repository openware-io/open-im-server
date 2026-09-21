package com.gvchat.im.user.api.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.im.user.api.converter.FriendApiConverter;
import com.gvchat.im.user.api.dto.request.HandleFriendRequest;
import com.gvchat.im.user.api.dto.request.SendFriendRequest;
import com.gvchat.im.user.api.dto.request.UpdateFriendRequest;
import com.gvchat.im.user.api.dto.response.FriendRequestResponse;
import com.gvchat.im.user.api.dto.response.FriendResponse;
import com.gvchat.im.user.application.social.FriendApplicationService;
import com.gvchat.im.user.application.social.command.HandleFriendRequestCommand;
import com.gvchat.im.user.application.social.command.SendFriendRequestCommand;
import com.gvchat.im.user.application.social.command.UpdateFriendCommand;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "好友管理")
@RequestMapping("/friends")
@RequiredArgsConstructor
public class FriendController {
  private final FriendApplicationService friendApplicationService;

  @PostMapping("/request")
  public FriendRequestResponse sendRequest(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody SendFriendRequest request) {
    return FriendApiConverter.toResponse(friendApplicationService.sendRequest(
        user.getId(), new SendFriendRequestCommand(request.toUserId(), request.message(), request.source(), request.groupId())));
  }

  @PutMapping("/request/{id}")
  public FriendRequestResponse handleRequest(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody HandleFriendRequest request) {
    return FriendApiConverter.toResponse(friendApplicationService.handleRequest(
        user.getId(), id, new HandleFriendRequestCommand(request.action())));
  }

  @GetMapping("/requests/pending")
  public List<FriendRequestResponse> getPendingRequests(@AuthenticationPrincipal SecurityUser user) {
    return friendApplicationService.getPendingRequests(user.getId()).stream().map(FriendApiConverter::toResponse).toList();
  }

  @GetMapping
  public List<FriendResponse> getFriendList(@AuthenticationPrincipal SecurityUser user) {
    return friendApplicationService.getFriendList(user.getId()).stream().map(FriendApiConverter::toResponse).toList();
  }

  @GetMapping("/groups")
  public List<String> getFriendGroups(@AuthenticationPrincipal SecurityUser user) {
    return friendApplicationService.getFriendGroups(user.getId());
  }

  @GetMapping("/blocked")
  public List<FriendResponse> getBlockedList(@AuthenticationPrincipal SecurityUser user) {
    return friendApplicationService.getBlockedList(user.getId()).stream().map(FriendApiConverter::toResponse).toList();
  }

  @PutMapping("/{friendId}")
  public FriendResponse updateFriend(@AuthenticationPrincipal SecurityUser user, @PathVariable Long friendId,
      @Valid @RequestBody UpdateFriendRequest request) {
    return FriendApiConverter.toResponse(friendApplicationService.updateFriend(
        user.getId(), friendId, new UpdateFriendCommand(request.remark(), request.groupName())));
  }

  @PostMapping("/{friendId}/block")
  public FriendResponse blockFriend(@AuthenticationPrincipal SecurityUser user, @PathVariable Long friendId) {
    return FriendApiConverter.toResponse(friendApplicationService.blockFriend(user.getId(), friendId));
  }

  @PostMapping("/{friendId}/unblock")
  public FriendResponse unblockFriend(@AuthenticationPrincipal SecurityUser user, @PathVariable Long friendId) {
    return FriendApiConverter.toResponse(friendApplicationService.unblockFriend(user.getId(), friendId));
  }

  @DeleteMapping("/{friendId}")
  public void deleteFriend(@AuthenticationPrincipal SecurityUser user, @PathVariable Long friendId) {
    friendApplicationService.deleteFriend(user.getId(), friendId);
  }
}
