package io.openware.common.media.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.common.media.infra.persistence.media.mapper.MediaObjectMapper;
import io.openware.common.media.infra.persistence.media.po.MediaObjectPo;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import io.openware.common.media.api.media.MediaAccessUrlSnapshot;
import java.time.Instant;
import io.openware.common.media.config.MediaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaObjectService {
  private final MediaObjectMapper objectMapper;
  private final MediaStoragePort storage;
  private final MediaProperties properties;
  private final MediaAuditService audit;

  public Map<String, Object> status(long requesterId, boolean administrator, String objectId) {
    MediaObjectPo object = requireAuthorized(requesterId, administrator, objectId);
    return Map.of("objectId", object.getObjectId(), "status", object.getStatus().toLowerCase(),
        "contentType", object.getContentType(), "size", object.getSizeBytes());
  }

  public Map<String, Object> access(long requesterId, boolean administrator, String objectId) {
    MediaObjectPo object = requireAuthorized(requesterId, administrator, objectId);
    if (!"ACTIVE".equals(object.getStatus())) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "媒体对象未处于可用状态");
    }
    String url = storage.accessUrl(object.getBucketName(), object.getObjectKey());
    audit.recordAccessUrlSigned(requesterId, object.getObjectId(), object.getScope());
    return Map.of("objectId", object.getObjectId(), "url", url,
        "contentType", object.getContentType(), "size", object.getSizeBytes());
  }

  public List<Map<String, Object>> accessUrls(long requesterId, boolean administrator, List<String> objectIds) {
    if (objectIds == null || objectIds.isEmpty() || objectIds.size() > 100) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "媒体对象标识不合法");
    }
    return objectIds.stream().distinct().map(objectId -> access(requesterId, administrator, objectId)).toList();
  }

  public MediaAccessUrlSnapshot accessAuthorizedByBusiness(MediaObjectPo object) {
    if (!"ACTIVE".equals(object.getStatus())) throw new ApiException(HttpStatusCodes.NOT_FOUND, "媒体对象不存在");
    return new MediaAccessUrlSnapshot(object.getObjectId(), storage.accessUrl(object.getBucketName(), object.getObjectKey()),
        object.getContentType(), object.getSizeBytes(), Instant.now().plusSeconds(properties.accessUrlTtlSeconds()));
  }

  @Transactional
  public void delete(long requesterId, boolean administrator, String objectId) {
    MediaObjectPo object = requireAuthorized(requesterId, administrator, objectId);
    if ("DELETED".equals(object.getStatus())) return;
    storage.deleteObject(object.getBucketName(), object.getObjectKey());
    LocalDateTime now = LocalDateTime.now();
    object.setStatus("DELETED"); object.setDeletedAt(now); object.setUpdatedBy(requesterId); object.setUpdatedAt(now);
    objectMapper.updateById(object);
  }

  private MediaObjectPo requireAuthorized(long requesterId, boolean administrator, String objectId) {
    MediaObjectPo object = objectMapper.selectOne(Wrappers.<MediaObjectPo>lambdaQuery()
        .eq(MediaObjectPo::getObjectId, objectId));
    if (object == null || "DELETED".equals(object.getStatus())) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "媒体对象不存在");
    }
    if (!administrator && object.getOwnerId() != requesterId) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "无权访问该媒体对象");
    }
    return object;
  }

}
