package com.gvchat.common.media.controller;

import com.gvchat.infrastructure.security.SecurityUser;
import com.gvchat.common.media.media.MediaMultipartUploadService;
import com.gvchat.common.media.media.MediaUploadSessionService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/media/multipart-upload-sessions")
@RequiredArgsConstructor
public class MediaMultipartUploadController {
  private final MediaMultipartUploadService uploads;

  @PostMapping
  public Map<String, Object> create(@AuthenticationPrincipal SecurityUser user, @RequestHeader("Idempotency-Key") String key,
      @RequestBody MediaUploadSessionService.CreateUploadSession request) { return uploads.create(user.getId(), key, request); }

  @PostMapping("/{sessionId}/complete")
  public Map<String, Object> complete(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId,
      @RequestBody MediaMultipartUploadService.CompleteMultipartUpload request) {
    return uploads.complete(user.getId(), sessionId, request);
  }

  @PostMapping("/{sessionId}/parts/signatures")
  public Map<String, Object> signatures(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId,
      @RequestBody MediaMultipartUploadService.PartNumbers request) {
    return uploads.signatures(user.getId(), sessionId, request.partNumbers());
  }

  @PostMapping("/{sessionId}/parts/{partNumber}/complete")
  public void confirmPart(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId,
      @PathVariable int partNumber, @RequestBody MediaMultipartUploadService.PartConfirmation request) {
    uploads.confirmPart(user.getId(), sessionId, partNumber, request);
  }

  @DeleteMapping("/{sessionId}")
  public void cancel(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId) {
    uploads.cancel(user.getId(), sessionId);
  }

  @GetMapping("/{sessionId}")
  public Map<String, Object> status(@AuthenticationPrincipal SecurityUser user, @PathVariable String sessionId) {
    return uploads.status(user.getId(), sessionId);
  }
}
