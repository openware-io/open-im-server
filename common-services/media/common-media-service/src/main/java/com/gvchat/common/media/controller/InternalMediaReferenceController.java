package com.gvchat.common.media.controller;

import com.gvchat.common.media.media.MediaReferenceService;
import com.gvchat.common.media.api.media.MediaObjectAuthorizationRequest;
import com.gvchat.common.media.api.media.MediaObjectAuthorizationSnapshot;
import com.gvchat.common.media.api.media.BusinessMediaAccessRequest;
import com.gvchat.common.media.api.media.MediaAccessUrlSnapshot;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/media/references")
@RequiredArgsConstructor
public class InternalMediaReferenceController {
  private final MediaReferenceService references;

  @PostMapping
  public void bind(@RequestBody MediaReferenceService.BindMediaReference request) {
    references.bind(request);
  }

  @org.springframework.web.bind.annotation.DeleteMapping
  public void unbind(@RequestBody MediaReferenceService.UnbindMediaReference request) {
    references.unbind(request);
  }

  @org.springframework.web.bind.annotation.DeleteMapping("/by-business")
  public void unbindByBusiness(@RequestBody MediaReferenceService.UnbindByBusinessRequest request) {
    references.unbindByBusiness(request.businessType(), request.businessId());
  }

  @PostMapping("/authorize")
  public MediaObjectAuthorizationSnapshot authorize(@RequestBody MediaObjectAuthorizationRequest request) {
    return references.authorize(request);
  }

  @PostMapping("/access-urls")
  public MediaAccessUrlSnapshot accessUrls(@RequestBody BusinessMediaAccessRequest request) {
    return references.accessAuthorizedByBusiness(request);
  }

  /** 批量解析访问 URL，返回 objectId → url；用于列表回填避免逐条调用。 */
  @PostMapping("/access-urls/batch")
  public Map<String, String> accessUrlsBatch(@RequestBody List<BusinessMediaAccessRequest> requests) {
    return references.accessUrlsByBusiness(requests);
  }
}
