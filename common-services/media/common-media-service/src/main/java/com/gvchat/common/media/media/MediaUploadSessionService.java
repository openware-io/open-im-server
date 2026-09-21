package com.gvchat.common.media.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.media.config.MediaProperties;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaObjectMapper;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaUploadSessionMapper;
import com.gvchat.common.media.infra.persistence.media.po.MediaObjectPo;
import com.gvchat.common.media.infra.persistence.media.po.MediaUploadSessionPo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaUploadSessionService {
  private static final Set<String> SCOPES = Set.of("avatar", "chat", "reservation", "system");
  private static final Set<String> KINDS = Set.of("image", "audio", "video", "attachment", "voucher");
  private final MediaProperties properties;
  private final MediaStoragePort storage;
  private final MediaObjectMapper objectMapper;
  private final MediaUploadSessionMapper sessionMapper;
  private final ApplicationEventPublisher events;

  @Transactional
  public Map<String, Object> create(long ownerId, String idempotencyKey, CreateUploadSession request) {
    String scope = normalized(request.scope());
    String mediaKind = normalized(request.mediaKind());
    String contentType = normalized(request.contentType());
    String requestDigest = requestDigest(scope, mediaKind, contentType, request);
    validateRequest(scope, mediaKind, contentType, request);
    MediaUploadSessionPo existing = sessionMapper.selectOne(Wrappers.<MediaUploadSessionPo>lambdaQuery()
        .eq(MediaUploadSessionPo::getOwnerId, ownerId).eq(MediaUploadSessionPo::getIdempotencyKey, idempotencyKey));
    if (existing != null) {
      if (!existing.getRequestDigest().equals(requestDigest)) {
        throw new ApiException(HttpStatusCodes.CONFLICT, "幂等键与上传请求不一致");
      }
      return created(existing);
    }
    String sessionId = UUID.randomUUID().toString();
    String objectId = UUID.randomUUID().toString();
    String temporaryKey = "temp/upload/" + sessionId + "/" + objectId;
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiresAt = now.plusSeconds(properties.uploadSessionTtlSeconds());
    MediaObjectPo object = new MediaObjectPo();
    object.setObjectId(objectId); object.setOwnerId(ownerId); object.setProvider(properties.provider());
    object.setBucketName(properties.privateBucket()); object.setObjectKey(temporaryKey); object.setScope(scope);
    object.setMediaKind(mediaKind); object.setContentType(contentType); object.setOriginalFileName(request.fileName());
    object.setSizeBytes(request.size()); object.setChecksumSha256(request.sha256()); object.setDurationMs(request.durationMs());
    object.setUploadSessionId(sessionId); object.setStatus("PENDING"); object.setExpiresAt(expiresAt);
    object.setCreatedBy(ownerId); object.setCreatedAt(now); object.setUpdatedBy(ownerId); object.setUpdatedAt(now);
    objectMapper.insert(object);
    MediaUploadSessionPo session = new MediaUploadSessionPo();
    session.setUploadSessionId(sessionId); session.setObjectId(objectId); session.setOwnerId(ownerId);
    session.setIdempotencyKey(idempotencyKey); session.setRequestDigest(requestDigest); session.setProvider(properties.provider());
    session.setBucketName(properties.privateBucket()); session.setTemporaryObjectKey(temporaryKey); session.setScope(scope);
    session.setMediaKind(mediaKind); session.setContentType(contentType); session.setSizeBytes(request.size());
    session.setChecksumSha256(request.sha256()); session.setStatus("UPLOADING"); session.setExpiresAt(expiresAt);
    session.setCreatedBy(ownerId); session.setCreatedAt(now); session.setUpdatedBy(ownerId); session.setUpdatedAt(now);
    sessionMapper.insert(session);
    return created(session);
  }

  @Transactional
  public Map<String, Object> complete(long ownerId, String sessionId, CompleteUploadSession request) {
    MediaUploadSessionPo session = requireOwnedSession(ownerId, sessionId);
    if ("COMPLETED".equals(session.getStatus())) return completed(session);
    if (!"UPLOADING".equals(session.getStatus()) || session.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "上传会话不可用");
    }
    if (request.size() != session.getSizeBytes() || !session.getChecksumSha256().equals(request.sha256())
        || storage.objectSize(session.getBucketName(), session.getTemporaryObjectKey()) != session.getSizeBytes()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "已上传文件与上传会话不一致");
    }
    LocalDateTime now = LocalDateTime.now();
    session.setStatus("COMPLETED"); session.setCompletedAt(now); session.setUpdatedBy(ownerId); session.setUpdatedAt(now);
    sessionMapper.updateById(session);
    MediaObjectPo object = objectMapper.selectById(session.getObjectId());
    object.setStatus("UPLOADED"); object.setUpdatedBy(ownerId); object.setUpdatedAt(now);
    objectMapper.updateById(object);
    events.publishEvent(new MediaUploadCompletedEvent(sessionId));
    return completed(session);
  }

  public Map<String, Object> status(long ownerId, String sessionId) {
    MediaUploadSessionPo session = requireOwnedSession(ownerId, sessionId);
    return Map.of("uploadSessionId", session.getUploadSessionId(), "objectId", session.getObjectId(),
        "status", session.getStatus().toLowerCase(Locale.ROOT), "expiresAt", session.getExpiresAt().toString());
  }

  @Transactional
  public void cancel(long ownerId, String sessionId) {
    MediaUploadSessionPo session = requireOwnedSession(ownerId, sessionId);
    if ("COMPLETED".equals(session.getStatus())) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "已完成的上传会话不可取消");
    }
    storage.deleteObject(session.getBucketName(), session.getTemporaryObjectKey());
    LocalDateTime now = LocalDateTime.now();
    session.setStatus("CANCELLED"); session.setUpdatedBy(ownerId); session.setUpdatedAt(now); sessionMapper.updateById(session);
    MediaObjectPo object = objectMapper.selectById(session.getObjectId());
    object.setStatus("DELETED"); object.setDeletedAt(now); object.setUpdatedBy(ownerId); object.setUpdatedAt(now);
    objectMapper.updateById(object);
  }

  private Map<String, Object> created(MediaUploadSessionPo session) {
    Map<String, String> requiredHeaders = requiredHeaders(session);
    return Map.of("uploadSessionId", session.getUploadSessionId(), "objectId", session.getObjectId(), "method", "PUT",
        "uploadUrl", storage.uploadUrl(session.getBucketName(), session.getTemporaryObjectKey(), session.getContentType(),
            requiredHeaders),
        "requiredHeaders", requiredHeaders, "expiresAt", session.getExpiresAt().toString());
  }

  /** 直传预签名 PUT 必须携带的请求头：附件/凭证额外要求 Content-Disposition: attachment，图片/音视频不加（避免被强制下载）。 */
  private Map<String, String> requiredHeaders(MediaUploadSessionPo session) {
    Map<String, String> headers = new java.util.LinkedHashMap<>();
    headers.put("Content-Type", session.getContentType());
    if ("attachment".equals(session.getMediaKind()) || "voucher".equals(session.getMediaKind())) {
      headers.put("Content-Disposition", "attachment");
    }
    return headers;
  }

  private Map<String, Object> completed(MediaUploadSessionPo session) {
    return Map.of("objectId", session.getObjectId(), "status", "processing", "contentType", session.getContentType(),
        "size", session.getSizeBytes());
  }

  private MediaUploadSessionPo requireOwnedSession(long ownerId, String sessionId) {
    MediaUploadSessionPo session = sessionMapper.selectOne(Wrappers.<MediaUploadSessionPo>lambdaQuery()
        .eq(MediaUploadSessionPo::getUploadSessionId, sessionId).eq(MediaUploadSessionPo::getOwnerId, ownerId));
    if (session == null) throw new ApiException(HttpStatusCodes.NOT_FOUND, "上传会话不存在");
    return session;
  }

  private void validateRequest(String scope, String mediaKind, String contentType, CreateUploadSession request) {
    if (!SCOPES.contains(scope)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "不支持的上传业务类型");
    }
    if (!KINDS.contains(mediaKind)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "不支持的文件分类");
    }
    validateScopeKind(scope, mediaKind);
    Set<String> supportedTypes = supportedTypes(mediaKind);
    if (!supportedTypes.contains(contentType)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, unsupportedFormatMessage(mediaKind, contentType, supportedTypes));
    }
    if (request.size() <= 0) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "上传文件不能为空");
    }
    if (request.size() > maxSize(mediaKind)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "文件大小超出限制，" + mediaKindLabel(mediaKind)
          + "最大支持 " + readableSize(maxSize(mediaKind)));
    }
    if (request.sha256() == null || !request.sha256().matches("[0-9a-f]{64}")) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "上传请求参数不合法");
    }
    if (("audio".equals(mediaKind) || "video".equals(mediaKind))
        && (request.durationMs() == null || request.durationMs() <= 0
        || request.durationMs() > maxDuration(mediaKind))) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "媒体时长不合法");
    }
    if (!"audio".equals(mediaKind) && !"video".equals(mediaKind) && request.durationMs() != null) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "该文件类型不支持时长参数");
    }
  }

  private void validateScopeKind(String scope, String mediaKind) {
    if ("avatar".equals(scope) && !"image".equals(mediaKind)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "头像仅支持上传图片文件");
    }
    if ("reservation".equals(scope) && !"voucher".equals(mediaKind)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "预约仅支持上传凭证文件（JPG、PNG、PDF）");
    }
    if ("system".equals(scope) && !Set.of("image", "attachment").contains(mediaKind)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "系统附件仅支持图片或普通附件");
    }
  }

  private Set<String> supportedTypes(String mediaKind) {
    if ("voucher".equals(mediaKind)) return types(properties.allowedVoucherTypes());
    if ("image".equals(mediaKind)) return types(properties.allowedImageTypes());
    if ("audio".equals(mediaKind)) return types(properties.allowedAudioTypes());
    if ("video".equals(mediaKind)) return types(properties.allowedVideoTypes());
    return types(properties.allowedAttachmentTypes());
  }

  private long maxSize(String mediaKind) {
    if ("image".equals(mediaKind)) return properties.maxImageBytes();
    if ("audio".equals(mediaKind)) return properties.maxAudioBytes();
    if ("video".equals(mediaKind)) return properties.maxVideoBytes();
    return properties.maxAttachmentBytes();
  }

  private String unsupportedFormatMessage(String mediaKind, String contentType, Set<String> supportedTypes) {
    String actualType = contentType.isBlank() ? "未识别" : contentType;
    return "不支持的" + mediaKindLabel(mediaKind) + "格式（" + actualType + "），请上传："
        + supportedTypes.stream().map(this::displayType).sorted().collect(Collectors.joining("、"));
  }

  private String mediaKindLabel(String mediaKind) {
    return switch (mediaKind) {
      case "image" -> "图片";
      case "audio" -> "音频";
      case "video" -> "视频";
      case "voucher" -> "凭证";
      default -> "附件";
    };
  }

  private String displayType(String contentType) {
    return switch (contentType) {
      case "image/jpeg" -> "JPG";
      case "image/png" -> "PNG";
      case "image/webp" -> "WebP";
      case "audio/mpeg" -> "MP3";
      case "audio/ogg" -> "OGG";
      case "audio/wav" -> "WAV";
      case "audio/mp4" -> "M4A";
      case "audio/aac" -> "AAC";
      case "video/mp4" -> "MP4";
      case "application/pdf" -> "PDF";
      case "text/plain" -> "TXT";
      case "application/zip" -> "ZIP";
      default -> contentType;
    };
  }

  private String readableSize(long bytes) {
    if (bytes % (1024 * 1024) == 0) return bytes / (1024 * 1024) + "MB";
    return bytes / 1024 + "KB";
  }

  private Set<String> types(String values) {
    return java.util.Arrays.stream(values.split(",")).map(String::trim).filter(value -> !value.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
  private String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
  private long maxDuration(String mediaKind) {
    return "audio".equals(mediaKind) ? properties.maxAudioDurationMs() : properties.maxVideoDurationMs();
  }

  private String requestDigest(String scope, String mediaKind, String contentType, CreateUploadSession request) {
    try {
      String value = String.join("\n", scope, mediaKind, contentType, String.valueOf(request.size()), request.sha256(),
          request.fileName() == null ? "" : request.fileName(), String.valueOf(request.durationMs()));
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to create upload request digest", exception);
    }
  }

  public record CreateUploadSession(String scope, String mediaKind, String fileName, String contentType, long size,
                                    String sha256, Long durationMs) { }
  public record CompleteUploadSession(long size, String sha256) { }
}
