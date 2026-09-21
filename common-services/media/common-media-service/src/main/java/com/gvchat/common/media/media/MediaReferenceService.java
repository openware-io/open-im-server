package com.gvchat.common.media.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaObjectMapper;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaReferenceMapper;
import com.gvchat.common.media.infra.persistence.media.po.MediaObjectPo;
import com.gvchat.common.media.infra.persistence.media.po.MediaReferencePo;
import com.gvchat.common.media.api.media.MediaObjectAuthorizationRequest;
import com.gvchat.common.media.api.media.MediaObjectAuthorizationSnapshot;
import com.gvchat.common.media.api.media.BusinessMediaAccessRequest;
import com.gvchat.common.media.api.media.MediaAccessUrlSnapshot;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaReferenceService {
  private final MediaObjectMapper objectMapper;
  private final MediaReferenceMapper referenceMapper;
  private final MediaObjectService mediaObjects;
  private final MediaAuditService audit;

  @Transactional
  public void bind(BindMediaReference request) {
    long ownerId = request.ownerId();
    MediaObjectPo object = objectMapper.selectById(request.objectId());
    if (object == null || !"ACTIVE".equals(object.getStatus()) || object.getOwnerId() != ownerId) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "媒体对象不可绑定");
    }
    MediaReferencePo existing = referenceMapper.selectOne(Wrappers.<MediaReferencePo>lambdaQuery()
        .eq(MediaReferencePo::getObjectId, request.objectId())
        .eq(MediaReferencePo::getBusinessType, request.businessType())
        .eq(MediaReferencePo::getBusinessId, request.businessId())
        .eq(MediaReferencePo::getReferenceRole, request.referenceRole()));
    if (existing != null) return;
    MediaReferencePo reference = new MediaReferencePo();
    reference.setObjectId(request.objectId()); reference.setBusinessType(request.businessType());
    reference.setBusinessId(request.businessId()); reference.setReferenceRole(request.referenceRole());
    reference.setCreatedBy(ownerId); reference.setCreatedAt(LocalDateTime.now());
    referenceMapper.insert(reference);
  }

  public MediaObjectAuthorizationSnapshot authorize(MediaObjectAuthorizationRequest request) {
    MediaObjectPo object = objectMapper.selectById(request.objectId());
    boolean authorized = object != null && "ACTIVE".equals(object.getStatus())
        && object.getOwnerId() == request.ownerId() && object.getMediaKind().equals(request.mediaKind());
    return new MediaObjectAuthorizationSnapshot(authorized, request.objectId(),
        object == null ? null : object.getContentType(), object == null ? 0L : object.getSizeBytes());
  }

  @Transactional
  public void unbind(UnbindMediaReference request) {
    referenceMapper.delete(Wrappers.<MediaReferencePo>lambdaQuery()
        .eq(MediaReferencePo::getObjectId, request.objectId())
        .eq(MediaReferencePo::getBusinessType, request.businessType())
        .eq(MediaReferencePo::getBusinessId, request.businessId())
        .eq(MediaReferencePo::getReferenceRole, request.referenceRole()));
  }

  /** 按业务标识解除该业务（如某条消息）的全部媒体引用；用于消息硬删除/销毁时贯通媒体清理。 */
  @Transactional
  public int unbindByBusiness(String businessType, String businessId) {
    return referenceMapper.delete(Wrappers.<MediaReferencePo>lambdaQuery()
        .eq(MediaReferencePo::getBusinessType, businessType)
        .eq(MediaReferencePo::getBusinessId, businessId));
  }

  public MediaAccessUrlSnapshot accessAuthorizedByBusiness(BusinessMediaAccessRequest request) {
    MediaReferencePo reference = referenceMapper.selectOne(Wrappers.<MediaReferencePo>lambdaQuery()
        .eq(MediaReferencePo::getObjectId, request.objectId()).eq(MediaReferencePo::getBusinessType, request.businessType())
        .eq(MediaReferencePo::getBusinessId, request.businessId()));
    if (reference == null) throw new ApiException(HttpStatusCodes.NOT_FOUND, "媒体对象引用不存在");
    MediaObjectPo object = objectMapper.selectById(request.objectId());
    if (object == null) throw new ApiException(HttpStatusCodes.NOT_FOUND, "媒体对象不存在");
    audit.recordAccessUrlSigned(0L, object.getObjectId(), request.businessType());
    return mediaObjects.accessAuthorizedByBusiness(object);
  }

  /** 批量解析业务引用访问 URL（如用户头像列表回填），单次批量查询避免逐条 N+1。 */
  public Map<String, String> accessUrlsByBusiness(List<BusinessMediaAccessRequest> requests) {
    if (requests == null || requests.isEmpty()) return Map.of();
    List<String> objectIds = requests.stream()
        .map(BusinessMediaAccessRequest::objectId)
        .filter(Objects::nonNull)
        .filter(id -> !id.isBlank())
        .distinct()
        .toList();
    if (objectIds.isEmpty()) return Map.of();
    List<MediaObjectPo> objects = objectMapper.selectList(
        Wrappers.<MediaObjectPo>lambdaQuery().in(MediaObjectPo::getObjectId, objectIds));
    Map<String, MediaObjectPo> byId = new HashMap<>();
    for (MediaObjectPo object : objects) byId.put(object.getObjectId(), object);
    Map<String, String> urls = new HashMap<>();
    for (BusinessMediaAccessRequest request : requests) {
      MediaObjectPo object = byId.get(request.objectId());
      if (object == null || !"ACTIVE".equals(object.getStatus())) continue;
      audit.recordAccessUrlSigned(0L, object.getObjectId(), request.businessType());
      urls.put(request.objectId(), mediaObjects.accessAuthorizedByBusiness(object).url());
    }
    return urls;
  }

  public record BindMediaReference(long ownerId, String objectId, String businessType, String businessId,
                                   String referenceRole) { }
  public record UnbindMediaReference(String objectId, String businessType, String businessId,
                                     String referenceRole) { }
  public record UnbindByBusinessRequest(String businessType, String businessId) { }
}
