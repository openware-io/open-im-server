package com.gvchat.common.media.controller;

import com.gvchat.common.media.media.ReleaseArtifactService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支撑服务内部接口：供管理服务为客户端发布制品发起预签名直传（内部 HMAC 鉴权）。 */
@RestController
@RequestMapping("/internal/media/release-artifacts")
@RequiredArgsConstructor
public class InternalReleaseArtifactController {
  private final ReleaseArtifactService service;

  @PostMapping("/upload-sessions")
  public Map<String, Object> createUpload(@RequestBody CreateUploadRequest request) {
    return service.createUpload(request.platform(), request.fileName(), request.contentType());
  }

  @PostMapping("/upload-sessions/complete")
  public Map<String, Object> completeUpload(@RequestBody CompleteUploadRequest request) {
    return service.completeUpload(request.platform(), request.fileName(), request.contentType(), request.objectKey());
  }

  public record CreateUploadRequest(String platform, String fileName, String contentType) { }
  public record CompleteUploadRequest(String platform, String fileName, String contentType, String objectKey) { }
}
