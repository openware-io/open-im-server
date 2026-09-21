package com.gvchat.im.admin.api.clientrelease;

import com.gvchat.im.admin.media.ReleaseArtifactUploadClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 客户端发布制品上传：后台点选文件，浏览器预签名直传对象存储，返回下载 URL / SHA-256 / 大小，免手工填写。 */
@RestController
@RequestMapping("/admin/client-releases")
@RequiredArgsConstructor
public class ClientReleaseArtifactController {
  private final ReleaseArtifactUploadClient uploadClient;

  @PostMapping("/artifacts/upload-sessions")
  public Map<String, Object> createUpload(@RequestBody CreateUploadRequest request) {
    return uploadClient.createUpload(request.platform(), request.fileName(), request.contentType());
  }

  @PostMapping("/artifacts/upload-sessions/complete")
  public Map<String, Object> completeUpload(@RequestBody CompleteUploadRequest request) {
    return uploadClient.completeUpload(request.platform(), request.fileName(), request.contentType(), request.objectKey());
  }

  public record CreateUploadRequest(String platform, String fileName, String contentType) { }
  public record CompleteUploadRequest(String platform, String fileName, String contentType, String objectKey) { }
}
