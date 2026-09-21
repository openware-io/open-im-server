package com.gvchat.im.conversation.api.controller.internal;

import com.gvchat.im.conversation.application.channel.ChannelApplicationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/channels")
@RequiredArgsConstructor
public class ChannelAuthorizationController {
  private final ChannelApplicationService channelService;

  @GetMapping("/{channelId}/owner")
  public Map<String, Boolean> isOwner(@PathVariable long channelId, @RequestParam long userId) {
    return Map.of("owner", channelService.isOwner(userId, channelId));
  }

  @GetMapping("/{channelId}/subscribed")
  public Map<String, Boolean> isSubscribed(@PathVariable long channelId, @RequestParam long userId) {
    return Map.of("subscribed", channelService.isSubscribed(channelId, userId));
  }

  @GetMapping("/{channelId}/subscriber-ids")
  public List<Long> subscriberIds(@PathVariable long channelId) {
    return channelService.listSubscriberUserIds(channelId);
  }
}
