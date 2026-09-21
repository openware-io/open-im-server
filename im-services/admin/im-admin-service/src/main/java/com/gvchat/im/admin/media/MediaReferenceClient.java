package com.gvchat.im.admin.media;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.common.media.api.media.MediaObjectAuthorizationRequest;
import com.gvchat.common.media.api.media.MediaObjectAuthorizationSnapshot;
import com.gvchat.common.media.api.media.BusinessMediaAccessRequest;
import com.gvchat.common.media.api.media.MediaAccessUrlSnapshot;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class MediaReferenceClient {
  private final RestClient.Builder restClientBuilder;
  private final MediaServiceProperties properties;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  public void authorizeAndBind(long ownerId, String objectId, String businessId) {
    if (objectId == null || objectId.isBlank()) return;
    MediaObjectAuthorizationSnapshot authorization = client().post().uri("/internal/media/references/authorize")
        .body(new MediaObjectAuthorizationRequest(ownerId, objectId, "image")).retrieve()
        .body(MediaObjectAuthorizationSnapshot.class);
    if (authorization == null || !authorization.authorized()) {
      throw new IllegalArgumentException("Media object cannot be used as a service icon");
    }
    client().post().uri("/internal/media/references").body(Map.of("ownerId", ownerId, "objectId", objectId,
        "businessType", "miniapp_service", "businessId", businessId, "referenceRole", "icon"))
        .retrieve().toBodilessEntity();
  }

  public void unbind(String objectId, String businessId) {
    if (objectId == null || objectId.isBlank()) return;
    client().method(HttpMethod.DELETE).uri("/internal/media/references").body(Map.of("objectId", objectId,
        "businessType", "miniapp_service", "businessId", businessId, "referenceRole", "icon"))
        .retrieve().toBodilessEntity();
  }

  public String accessUrl(long requesterId, String objectId, String businessId) {
    if (objectId == null || objectId.isBlank()) return "";
    MediaAccessUrlSnapshot snapshot = client().post().uri("/internal/media/references/access-urls")
        .body(new BusinessMediaAccessRequest(objectId, "miniapp_service", businessId))
        .retrieve().body(MediaAccessUrlSnapshot.class);
    return snapshot == null ? "" : snapshot.url();
  }

  private RestClient client() {
    return restClientBuilder.clone().baseUrl(properties.getBaseUrl()).requestInterceptor(authenticationInterceptor).build();
  }
}
