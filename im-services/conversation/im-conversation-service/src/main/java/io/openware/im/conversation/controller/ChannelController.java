package io.openware.im.conversation.controller;

import io.openware.im.conversation.api.channel.ChannelResult;
import io.openware.im.conversation.api.channel.CreateChannelRequest;
import io.openware.im.conversation.api.channel.UpdateChannelRequest;
import io.openware.im.conversation.application.channel.ChannelApplicationService;
import io.openware.infrastructure.security.SecurityUser;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "频道管理")
@RequestMapping("/channels")
@RequiredArgsConstructor
public class ChannelController {
  private final ChannelApplicationService channelService;

  @PostMapping
  public ChannelResult createChannel(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody CreateChannelRequest dto) {
    return channelService.createChannel(user.getId(), dto);
  }

  @GetMapping("/mine")
  public List<ChannelResult> getMyChannels(@AuthenticationPrincipal SecurityUser user) {
    return channelService.listMyChannels(user.getId());
  }

  @GetMapping("/{id}")
  public ChannelResult getChannel(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return channelService.getChannel(user.getId(), id);
  }

  @PostMapping("/{id}/subscribe")
  public ChannelResult subscribe(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    return channelService.subscribe(user.getId(), id);
  }

  @DeleteMapping("/{id}/subscribe")
  public Map<String, Object> unsubscribe(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    channelService.unsubscribe(user.getId(), id);
    return Map.of("unsubscribed", true);
  }

  @PutMapping("/{id}")
  public ChannelResult update(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id,
      @Valid @RequestBody UpdateChannelRequest dto) {
    return channelService.updateChannel(user.getId(), id, dto.name(), dto.avatar(), dto.announcement());
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(@AuthenticationPrincipal SecurityUser user, @PathVariable Long id) {
    channelService.deleteChannel(user.getId(), id);
    return Map.of("deleted", true);
  }

  @GetMapping("/search")
  public List<ChannelResult> search(@AuthenticationPrincipal SecurityUser user,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "20") int limit) {
    return channelService.searchChannels(user.getId(), keyword, limit);
  }

  @GetMapping("/by-code/{code}")
  public ChannelResult getByCode(@AuthenticationPrincipal SecurityUser user, @PathVariable String code) {
    return channelService.getChannelByCode(user.getId(), code);
  }
}
