package com.gvchat.im.admin.media;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 客户端发布制品预签名直传：管理服务仅转发会话创建/完成，二进制由浏览器直传对象存储。 */
@Component
@RequiredArgsConstructor
public class ReleaseArtifactUploadClient {
  private final RestClient.Builder restClientBuilder;
  private final MediaServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  public Map<String, Object> createUpload(String platform, String fileName, String contentType) {
    return client().post()
        .uri("/internal/media/release-artifacts/upload-sessions")
        .body(Map.of("platform", platform, "fileName", fileName, "contentType", contentType))
        .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
  }

  public Map<String, Object> completeUpload(String platform, String fileName, String contentType, String objectKey) {
    return client().post()
        .uri("/internal/media/release-artifacts/upload-sessions/complete")
        .body(Map.of("platform", platform, "fileName", fileName, "contentType", contentType, "objectKey", objectKey))
        .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
  }

  private RestClient client() {
    return restClientBuilder.clone().baseUrl(properties.getBaseUrl())
        .requestInterceptor(authenticationInterceptor).build();
  }
}
