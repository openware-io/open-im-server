package io.openware.im.conversation.controller;

import io.openware.im.conversation.api.mute.ConversationMuteResponse;
import io.openware.im.conversation.api.mute.MuteConversationRequest;
import io.openware.im.conversation.application.mute.ConversationMuteApplicationService;
import io.openware.infrastructure.security.SecurityUser;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会话免打扰：客户端切换单会话「免打扰」并跨端同步。 */
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationMuteController {
  private final ConversationMuteApplicationService conversationMuteApplicationService;

  @PutMapping("/{conversationId}/mute")
  public ConversationMuteResponse setMuted(@AuthenticationPrincipal SecurityUser user,
      @PathVariable String conversationId, @Valid @RequestBody MuteConversationRequest request) {
    boolean muted = conversationMuteApplicationService.setMuted(user.getId(), conversationId, request.muted());
    return new ConversationMuteResponse(conversationId, muted);
  }

  @GetMapping("/muted")
  public List<ConversationMuteResponse> getMuted(@AuthenticationPrincipal SecurityUser user) {
    return conversationMuteApplicationService.getMutedConversationIds(user.getId()).stream()
        .map(conversationId -> new ConversationMuteResponse(conversationId, true)).toList();
  }
}
