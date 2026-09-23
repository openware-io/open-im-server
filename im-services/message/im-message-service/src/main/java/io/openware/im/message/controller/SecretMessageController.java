package io.openware.im.message.controller;

import io.openware.infrastructure.security.SecurityUser;
import io.openware.im.message.api.secretmessage.PostSecretMessageRequest;
import io.openware.im.message.application.secretmessage.SecretMessageApplicationService;
import io.openware.im.message.application.secretmessage.command.PostSecretMessageCommand;
import io.openware.im.message.application.secretmessage.result.SecretMessageResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
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
@Tag(name = "私密消息")
@RequestMapping("/secret-messages")
@RequiredArgsConstructor
public class SecretMessageController {
  private final SecretMessageApplicationService secretMessageService;

  @PostMapping
  public SecretMessageResult post(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody PostSecretMessageRequest dto) {
    return secretMessageService.post(user.getId(),
        new PostSecretMessageCommand(dto.getSecretChatId(), dto.getMsgId(), dto.getCiphertext(),
            dto.getMediaObjectIds()));
  }

  @GetMapping
  public List<SecretMessageResult> list(@AuthenticationPrincipal SecurityUser user,
      @RequestParam long secretChatId, @RequestParam(defaultValue = "0") long afterSeq,
      @RequestParam(defaultValue = "50") int limit) {
    return secretMessageService.list(user.getId(), secretChatId, afterSeq, limit);
  }

  /** 重新换取媒体访问 URL（参与方授权）：媒体签名 URL 过期后按消息引用换新鲜 URL。 */
  @PostMapping("/media/access-urls")
  public List<Map<String, Object>> mediaAccessUrls(@AuthenticationPrincipal SecurityUser user,
      @RequestBody MediaAccessUrlsRequest dto) {
    return secretMessageService.accessUrls(user.getId(), dto.secretChatId(), dto.msgId(), dto.objectIds());
  }

  public record MediaAccessUrlsRequest(long secretChatId, String msgId, List<String> objectIds) {
  }

  /**
   * 接收方已读上报：对 {@code seq <= afterSeq} 且由对方发送、尚未计时的消息开始
   * 定时销毁倒计时。仅当接收方真正查阅（解密展示）到消息后才应调用——未查阅的
   * 消息不进入计时，杜绝「消息还没被查阅就销毁」。
   *
   * <p>响应携带本次计时截止时刻 destroyAt，客户端据此设置本地销毁定时器
   * （消息销毁事件无推送，客户端须本地主动移除）。</p>
   */
  @PostMapping("/{secretChatId}/read")
  public Map<String, Object> markRead(@AuthenticationPrincipal SecurityUser user,
      @PathVariable long secretChatId, @RequestBody MarkSecretReadRequest dto) {
    var result = secretMessageService.markRead(user.getId(), secretChatId, dto.afterSeq());
    Map<String, Object> response = new java.util.HashMap<>();
    response.put("secretChatId", secretChatId);
    response.put("counted", result.counted());
    response.put("destroyAt", result.destroyAt());
    return response;
  }

  /**
   * 会话销毁状态：返回 active 消息中最早的销毁时刻（无计时消息为 null）。
   * 客户端据此设置会话级刷新定时器，到期全量拉取以同步已销毁消息。
   */
  @GetMapping("/{secretChatId}/status")
  public Map<String, Object> status(@AuthenticationPrincipal SecurityUser user,
      @PathVariable long secretChatId) {
    Map<String, Object> response = new java.util.HashMap<>();
    response.put("secretChatId", secretChatId);
    response.put("earliestDestroyAt", secretMessageService.earliestDestroyAt(user.getId(), secretChatId));
    return response;
  }

  /**
   * 销毁状态增量同步（服务端权威）：返回销毁时刻晚于 {@code afterDestroyAt} 的
   * destroyed 消息（msgId + destroyAt）。端侧按此移除本地消息；销毁计时与执行
   * 完全由服务端控制，端侧不持有销毁定时器。
   */
  @GetMapping("/{secretChatId}/states")
  public Map<String, Object> destroyedStates(@AuthenticationPrincipal SecurityUser user,
      @PathVariable long secretChatId,
      @RequestParam(required = false) String afterDestroyAt,
      @RequestParam(defaultValue = "100") int limit) {
    LocalDateTime cursor = afterDestroyAt == null || afterDestroyAt.isBlank()
        ? LocalDateTime.of(1970, 1, 1, 0, 0)
        : LocalDateTime.parse(afterDestroyAt);
    var states = secretMessageService.listDestroyedStates(user.getId(), secretChatId, cursor, limit);
    Map<String, Object> response = new java.util.HashMap<>();
    response.put("secretChatId", secretChatId);
    response.put("destroyed", states);
    return response;
  }

  /**
   * 撤回（仅发送方，窗口内）：标记 recalled，服务端协调对端渲染撤回墓碑。
   */
  @PostMapping("/{secretChatId}/recall/{msgId}")
  public Map<String, Object> recall(@AuthenticationPrincipal SecurityUser user,
      @PathVariable long secretChatId, @PathVariable String msgId) {
    secretMessageService.recall(user.getId(), secretChatId, msgId);
    Map<String, Object> response = new java.util.HashMap<>();
    response.put("secretChatId", secretChatId);
    response.put("msgId", msgId);
    response.put("reason", "recalled");
    return response;
  }

  /**
   * 删除（任意参与方）：标记 destroyed，服务端协调对端移除本地消息。
   */
  @DeleteMapping("/{secretChatId}/{msgId}")
  public Map<String, Object> deleteForEveryone(@AuthenticationPrincipal SecurityUser user,
      @PathVariable long secretChatId, @PathVariable String msgId) {
    secretMessageService.deleteForEveryone(user.getId(), secretChatId, msgId);
    Map<String, Object> response = new java.util.HashMap<>();
    response.put("secretChatId", secretChatId);
    response.put("msgId", msgId);
    response.put("reason", "deleted");
    return response;
  }

  /**
   * 「删除仅我」：只把消息对当前用户隐藏，不影响对方、不销毁密文本体。
   *
   * <p>墓碑落服务端持久表，卸载重装后重新拉取密聊列表也不会把消息“复活”
   * （与普通消息同一套语义）。
   */
  @PostMapping("/delete-for-me")
  public Map<String, Object> deleteForMe(@AuthenticationPrincipal SecurityUser user,
      @Valid @RequestBody DeleteSecretForMeRequest request) {
    int marked = secretMessageService.deleteForMe(user.getId(), request.secretChatId(), request.msgIds());
    Map<String, Object> response = new java.util.HashMap<>();
    response.put("ok", true);
    response.put("deleted", marked);
    return response;
  }

  /** 「删除仅我」请求体（密聊）。 */
  public record DeleteSecretForMeRequest(long secretChatId,
      @jakarta.validation.constraints.NotEmpty List<String> msgIds) {
  }

  public record MarkSecretReadRequest(long afterSeq) {
  }
}
