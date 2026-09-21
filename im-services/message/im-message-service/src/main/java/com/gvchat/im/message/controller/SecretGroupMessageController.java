package com.gvchat.im.message.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.im.message.api.secretgroupmessage.PostSecretGroupMessageRequest;
import com.gvchat.im.message.api.secretgroupmessage.PostSecretGroupMessageRequest.RecipientRequest;
import com.gvchat.im.message.application.secretgroupmessage.SecretGroupMessageApplicationService;
import com.gvchat.im.message.application.secretgroupmessage.command.PostSecretGroupMessageCommand;
import com.gvchat.im.message.application.secretgroupmessage.command.PostSecretGroupMessageCommand.RecipientCiphertext;
import com.gvchat.im.message.application.secretgroupmessage.result.SecretGroupMessageResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "私密群聊消息")
@RequestMapping("/secret-group-messages")
@RequiredArgsConstructor
public class SecretGroupMessageController {
  private final SecretGroupMessageApplicationService service;

  @PostMapping
  public List<SecretGroupMessageResult> post(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody PostSecretGroupMessageRequest dto) {
    List<RecipientCiphertext> recipients = dto.getRecipients().stream()
        .map((RecipientRequest r) -> new RecipientCiphertext(r.getUserId(), r.getCiphertext()))
        .toList();
    return service.post(user.getId(), new PostSecretGroupMessageCommand(dto.getSecretGroupId(), dto.getMsgId(), recipients,
        dto.getMediaObjectIds(), dto.getAtUserIds()));
  }

  @PostMapping("/edit")
  public List<SecretGroupMessageResult> edit(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody PostSecretGroupMessageRequest dto) {
    List<RecipientCiphertext> recipients = dto.getRecipients().stream()
        .map((RecipientRequest r) -> new RecipientCiphertext(r.getUserId(), r.getCiphertext()))
        .toList();
    return service.edit(user.getId(), new PostSecretGroupMessageCommand(dto.getSecretGroupId(), dto.getMsgId(), recipients,
        dto.getMediaObjectIds(), dto.getAtUserIds()));
  }

  /** 重新换取媒体访问 URL（参与方授权）：媒体签名 URL 过期后按消息引用换新鲜 URL。 */
  @PostMapping("/media/access-urls")
  public List<Map<String, Object>> mediaAccessUrls(@AuthenticationPrincipal SecurityUser user,
      @RequestBody MediaAccessUrlsRequest dto) {
    return service.accessUrls(user.getId(), dto.secretGroupId(), dto.msgId(), dto.objectIds());
  }

  public record MediaAccessUrlsRequest(long secretGroupId, String msgId, List<String> objectIds) {
  }

  @GetMapping
  public List<SecretGroupMessageResult> list(@AuthenticationPrincipal SecurityUser user,
      @RequestParam long secretGroupId, @RequestParam(defaultValue = "0") long afterSeq,
      @RequestParam(defaultValue = "50") int limit) {
    return service.list(user.getId(), secretGroupId, afterSeq, limit);
  }

  /** 接收方已读上报：对 seq<=afterSeq 且由对方发送、未计时的消息按群销毁策略开始倒计时。 */
  @PostMapping("/read")
  public Map<String, Object> markRead(@AuthenticationPrincipal SecurityUser user,
      @RequestBody MarkReadRequest dto) {
    var result = service.markRead(user.getId(), dto.secretGroupId(), dto.afterSeq());
    Map<String, Object> response = new HashMap<>();
    response.put("secretGroupId", dto.secretGroupId());
    response.put("counted", result.counted());
    response.put("destroyAt", result.destroyAt());
    return response;
  }

  /** 销毁状态增量同步（服务端权威）：返回撤回/删除痕迹（msgId + destroyAt + reason）。 */
  @GetMapping("/destroyed-states")
  public Map<String, Object> destroyedStates(@AuthenticationPrincipal SecurityUser user,
      @RequestParam long secretGroupId,
      @RequestParam(required = false) String afterDestroyAt,
      @RequestParam(defaultValue = "100") int limit) {
    LocalDateTime cursor = afterDestroyAt == null || afterDestroyAt.isBlank()
        ? LocalDateTime.of(1970, 1, 1, 0, 0)
        : LocalDateTime.parse(afterDestroyAt);
    var states = service.listDestroyedStates(user.getId(), secretGroupId, cursor, limit);
    Map<String, Object> response = new HashMap<>();
    response.put("secretGroupId", secretGroupId);
    response.put("destroyed", states);
    return response;
  }

  /** 撤回（仅发送方，不限时）：服务端协调全员移除。 */
  @PostMapping("/recall/{msgId}")
  public Map<String, Object> recall(@AuthenticationPrincipal SecurityUser user,
      @RequestParam long secretGroupId, @PathVariable String msgId) {
    service.recall(user.getId(), secretGroupId, msgId);
    Map<String, Object> response = new HashMap<>();
    response.put("secretGroupId", secretGroupId);
    response.put("msgId", msgId);
    response.put("reason", "recalled");
    return response;
  }

  /** 删除所有人（仅发送方，不限时）：服务端协调全员移除。 */
  @DeleteMapping("/{msgId}")
  public Map<String, Object> deleteForEveryone(@AuthenticationPrincipal SecurityUser user,
      @RequestParam long secretGroupId, @PathVariable String msgId) {
    service.deleteForEveryone(user.getId(), secretGroupId, msgId);
    Map<String, Object> response = new HashMap<>();
    response.put("secretGroupId", secretGroupId);
    response.put("msgId", msgId);
    response.put("reason", "deleted");
    return response;
  }

  /**
   * 「删除仅我」：只把消息对当前成员隐藏，不影响其他成员、不销毁密文本体。
   *
   * <p>墓碑落服务端持久表，卸载重装后重新拉取也不会把消息“复活”（与普通消息同一套语义）。
   */
  @PostMapping("/delete-for-me")
  public Map<String, Object> deleteForMe(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody DeleteGroupSecretForMeRequest request) {
    int marked = service.deleteForMe(user.getId(), request.secretGroupId(), request.msgIds());
    Map<String, Object> response = new HashMap<>();
    response.put("ok", true);
    response.put("deleted", marked);
    return response;
  }

  /** 「删除仅我」请求体（密群）。 */
  public record DeleteGroupSecretForMeRequest(long secretGroupId,
      @jakarta.validation.constraints.NotEmpty List<String> msgIds) {
  }

  public record MarkReadRequest(long secretGroupId, long afterSeq) {
  }
}
