package io.openware.im.user.media;

import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import io.openware.common.media.api.media.BusinessMediaAccessRequest;
import io.openware.common.media.api.media.MediaAccessUrlSnapshot;
import io.openware.common.media.api.media.MediaObjectAuthorizationRequest;
import io.openware.common.media.api.media.MediaObjectAuthorizationSnapshot;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class MediaReferenceClient {
  private final RestClient.Builder restClientBuilder;
  private final MediaServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  public void authorizeAndBind(long userId, String objectId) {
    if (objectId == null || objectId.isBlank()) return;
    MediaObjectAuthorizationSnapshot authorization = client().post().uri("/internal/media/references/authorize")
        .body(new MediaObjectAuthorizationRequest(userId, objectId, "image")).retrieve()
        .body(MediaObjectAuthorizationSnapshot.class);
    if (authorization == null || !authorization.authorized()) throw new IllegalArgumentException("Invalid avatar media object");
    client().post().uri("/internal/media/references").body(Map.of("ownerId", userId, "objectId", objectId,
        "businessType", "user_profile", "businessId", String.valueOf(userId), "referenceRole", "avatar"))
        .retrieve().toBodilessEntity();
  }

  public void unbind(String objectId, long userId) {
    if (objectId == null || objectId.isBlank()) return;
    client().method(HttpMethod.DELETE).uri("/internal/media/references").body(Map.of("objectId", objectId,
        "businessType", "user_profile", "businessId", String.valueOf(userId), "referenceRole", "avatar"))
        .retrieve().toBodilessEntity();
  }

  public String accessUrl(long requesterId, String objectId, long profileUserId) {
    if (objectId == null || objectId.isBlank()) return "";
    MediaAccessUrlSnapshot snapshot = client().post().uri("/internal/media/references/access-urls")
        .body(new BusinessMediaAccessRequest(objectId, "user_profile", String.valueOf(profileUserId)))
        .retrieve().body(MediaAccessUrlSnapshot.class);
    return snapshot == null ? "" : snapshot.url();
  }

  /** 批量解析头像访问 URL（objectId → url），列表回填时单次调用避免逐条 N+1；失败返回空映射。 */
  public Map<String, String> accessUrls(List<BusinessMediaAccessRequest> requests) {
    if (requests == null || requests.isEmpty()) return Map.of();
    try {
      Map<String, String> urls = client().post().uri("/internal/media/references/access-urls/batch")
          .body(requests).retrieve().body(new ParameterizedTypeReference<Map<String, String>>() {});
      return urls == null ? Map.of() : urls;
    } catch (RuntimeException exception) {
      return Map.of();
    }
  }

  private RestClient client() { return restClientBuilder.clone().baseUrl(properties.getBaseUrl()).requestInterceptor(authenticationInterceptor).build(); }
}
