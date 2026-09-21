package com.gvchat.im.conversation.controller;

import com.gvchat.im.conversation.api.secretchat.SetDestroyPolicyRequest;
import com.gvchat.im.conversation.api.secretchat.SubmitHandshakeRequest;
import com.gvchat.im.conversation.api.secretgroupchat.AddSecretGroupMemberRequest;
import com.gvchat.im.conversation.api.secretgroupchat.CreateSecretGroupChatRequest;
import com.gvchat.im.conversation.api.secretgroupchat.SecretGroupChatResult;
import com.gvchat.im.conversation.application.secretgroupchat.SecretGroupChatApplicationService;
import com.gvchat.infrastructure.security.SecurityUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
@Tag(name = "私密群聊")
@RequestMapping("/secret-group-chats")
@RequiredArgsConstructor
public class SecretGroupChatController {
  private final SecretGroupChatApplicationService service;

  @PostMapping
  public SecretGroupChatResult create(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody CreateSecretGroupChatRequest dto) {
    return service.createGroup(user.getId(), dto);
  }

  @GetMapping("/mine")
  public List<SecretGroupChatResult> mine(@AuthenticationPrincipal SecurityUser user) {
    return service.listMyGroups(user.getId());
  }

  @GetMapping("/{id}")
  public SecretGroupChatResult get(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return service.getGroup(user.getId(), id);
  }

  @PostMapping("/{id}/members")
  public SecretGroupChatResult addMember(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody AddSecretGroupMemberRequest dto) {
    return service.addMember(user.getId(), id, dto.getUserId());
  }

  @PostMapping("/{id}/handshake")
  public SecretGroupChatResult handshake(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody SubmitHandshakeRequest dto) {
    return service.submitPublicKey(user.getId(), id, dto.getPublicKey());
  }

  @PostMapping("/{id}/destroy-policy")
  public SecretGroupChatResult setDestroyPolicy(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody SetDestroyPolicyRequest dto) {
    return service.setDestroyPolicy(user.getId(), id, dto.getPolicy());
  }

  @PostMapping("/{id}/anonymous")
  public SecretGroupChatResult setAnonymous(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @RequestBody AnonymousRequest dto) {
    return service.setAnonymousEnabled(user.getId(), id, dto.enabled());
  }

  @PostMapping("/{id}/pin")
  public SecretGroupChatResult pin(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @RequestBody PinRequest dto) {
    return service.pinMessage(user.getId(), id, dto.msgId());
  }

  @PostMapping("/{id}/unpin")
  public SecretGroupChatResult unpin(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return service.unpinMessage(user.getId(), id);
  }

  @PostMapping("/{id}/invite")
  public SecretGroupChatResult invite(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return service.generateInvite(user.getId(), id);
  }

  @PostMapping("/{id}/owner-only-post")
  public SecretGroupChatResult setOwnerOnlyPost(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @RequestBody AnonymousRequest dto) {
    return service.setOwnerOnlyPost(user.getId(), id, dto.enabled());
  }

  @PostMapping("/{id}/name")
  public SecretGroupChatResult rename(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @RequestBody NameRequest dto) {
    return service.renameGroup(user.getId(), id, dto.name());
  }

  @PostMapping("/{id}/announcement")
  public SecretGroupChatResult setAnnouncement(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @RequestBody AnnouncementRequest dto) {
    return service.setAnnouncement(user.getId(), id, dto.announcement());
  }

  @DeleteMapping("/{id}/members/{userId}")
  public SecretGroupChatResult removeMember(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @PathVariable Long userId) {
    return service.removeMember(user.getId(), id, userId);
  }

  @PostMapping("/join")
  public SecretGroupChatResult join(@AuthenticationPrincipal SecurityUser user, @RequestBody JoinRequest dto) {
    return service.joinByInvite(user.getId(), dto.token());
  }

  public record AnonymousRequest(boolean enabled) {}

  public record PinRequest(String msgId) {}

  public record JoinRequest(String token) {}

  public record NameRequest(String name) {}

  public record AnnouncementRequest(String announcement) {}

  @PostMapping("/{id}/leave")
  public Map<String, Object> leave(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    service.leaveGroup(user.getId(), id);
    return Map.of("left", true);
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    service.deleteGroup(user.getId(), id);
    return Map.of("deleted", true);
  }
}
