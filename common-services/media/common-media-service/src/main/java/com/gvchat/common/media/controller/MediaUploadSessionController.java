package com.gvchat.common.media.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.common.media.media.MediaUploadSessionService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media/upload-sessions")
@RequiredArgsConstructor
public class MediaUploadSessionController {
  private final MediaUploadSessionService uploadSessions;

  @PostMapping
  public Map<String, Object> create(@AuthenticationPrincipal SecurityUser user,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody MediaUploadSessionService.CreateUploadSession request) {
    requireIdempotencyKey(idempotencyKey);
    return uploadSessions.create(user.getId(), idempotencyKey, request);
  }

  @PostMapping("/{sessionId}/complete")
  public Map<String, Object> complete(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody MediaUploadSessionService.CompleteUploadSession request) {
    requireIdempotencyKey(idempotencyKey);
    return uploadSessions.complete(user.getId(), sessionId, request);
  }

  @GetMapping("/{sessionId}")
  public Map<String, Object> status(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId) {
    return uploadSessions.status(user.getId(), sessionId);
  }

  @DeleteMapping("/{sessionId}")
  public void cancel(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId) {
    uploadSessions.cancel(user.getId(), sessionId);
  }

  private void requireIdempotencyKey(String idempotencyKey) {
    try {
      java.util.UUID.fromString(idempotencyKey);
    } catch (IllegalArgumentException exception) {
      // 客户端错误必须是 400 + 稳定 code，此前抛 IllegalArgumentException 会落到兜底变成 500 且丢失原因
      throw new com.gvchat.common.exception.ApiException(
          com.gvchat.common.http.HttpStatusCodes.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须是 UUID");
    }
  }
}
