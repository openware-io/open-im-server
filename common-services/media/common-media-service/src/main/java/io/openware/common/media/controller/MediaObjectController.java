package io.openware.common.media.controller;

import io.openware.infrastructure.security.SecurityUser;
import io.openware.common.media.media.MediaObjectService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaObjectController {
  private final MediaObjectService mediaObjects;

  @GetMapping("/{objectId}")
  public Map<String, Object> status(@AuthenticationPrincipal SecurityUser user, @PathVariable String objectId) {
    return mediaObjects.status(user.getId(), administrator(user), objectId);
  }

  @GetMapping("/{objectId}/access")
  public Map<String, Object> access(@AuthenticationPrincipal SecurityUser user, @PathVariable String objectId) {
    return mediaObjects.access(user.getId(), administrator(user), objectId);
  }

  @PostMapping("/access-urls")
  public List<Map<String, Object>> accessUrls(@AuthenticationPrincipal SecurityUser user,
      @RequestBody AccessUrlsRequest request) {
    return mediaObjects.accessUrls(user.getId(), administrator(user), request.objectIds());
  }

  @DeleteMapping("/{objectId}")
  public void delete(@AuthenticationPrincipal SecurityUser user, @PathVariable String objectId) {
    mediaObjects.delete(user.getId(), administrator(user), objectId);
  }

  private boolean administrator(SecurityUser user) {
    return user.getAuthorities().stream().anyMatch(value -> "ROLE_ADMIN".equals(value.getAuthority()));
  }

  public record AccessUrlsRequest(List<String> objectIds) { }
}
