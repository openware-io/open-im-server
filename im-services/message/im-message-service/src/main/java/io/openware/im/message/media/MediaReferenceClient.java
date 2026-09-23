package io.openware.im.message.media;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.common.media.api.media.BusinessMediaAccessRequest;
import io.openware.common.media.api.media.MediaAccessUrlSnapshot;
import io.openware.common.media.api.media.MediaObjectAuthorizationRequest;
import io.openware.common.media.api.media.MediaObjectAuthorizationSnapshot;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class MediaReferenceClient {
  private final RestClient.Builder restClientBuilder;
  private final MediaServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  public void authorizeAndBind(long ownerId, String objectId, String msgId, String mediaKind) {
    MediaObjectAuthorizationSnapshot authorization = client().post().uri("/internal/media/references/authorize")
        .body(new MediaObjectAuthorizationRequest(ownerId, objectId, mediaKind)).retrieve()
        .body(MediaObjectAuthorizationSnapshot.class);
    if (authorization == null || !authorization.authorized()) {
      throw new IllegalArgumentException("Invalid message media object");
    }
    client().post().uri("/internal/media/references").body(Map.of("ownerId", ownerId, "objectId", objectId,
        "businessType", "message", "businessId", msgId, "referenceRole", "attachment"))
        .retrieve().toBodilessEntity();
  }

  /** 私密消息绑定媒体引用：无需 mediaKind（私密内容加密，服务端不可见），仅绑定对象↔消息关联。 */
  public void bind(long ownerId, String objectId, String msgId) {
    client().post().uri("/internal/media/references").body(Map.of("ownerId", ownerId, "objectId", objectId,
        "businessType", "message", "businessId", msgId, "referenceRole", "attachment"))
        .retrieve().toBodilessEntity();
  }

  /** 硬删除消息时解除媒体引用：移除该消息对媒体对象的绑定，使对象进入无引用清理流程。 */
  public void unbind(String objectId, String msgId) {
    client().method(HttpMethod.DELETE).uri("/internal/media/references")
        .body(Map.of("objectId", objectId, "businessType", "message", "businessId", msgId,
            "referenceRole", "attachment"))
        .retrieve().toBodilessEntity();
  }

  /** 按业务标识（消息 id）解除全部媒体引用：用于私密消息/群消息销毁时清理媒体绑定。 */
  public void unbindByBusiness(String msgId) {
    client().method(HttpMethod.DELETE).uri("/internal/media/references/by-business")
        .body(Map.of("businessType", "message", "businessId", msgId))
        .retrieve().toBodilessEntity();
  }

  public String accessUrl(long requesterId, String objectId, String msgId) {
    MediaAccessUrlSnapshot snapshot = client().post().uri("/internal/media/references/access-urls")
        .body(new BusinessMediaAccessRequest(objectId, "message", msgId)).retrieve()
        .body(MediaAccessUrlSnapshot.class);
    return snapshot == null ? "" : snapshot.url();
  }

  private RestClient client() {
    return restClientBuilder.clone().baseUrl(properties.getBaseUrl())
        .requestInterceptor(authenticationInterceptor).build();
  }
}
