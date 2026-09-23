package io.openware.common.media.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.common.media.config.MediaProperties;
import io.openware.common.media.infra.persistence.media.mapper.MediaUploadSessionMapper;
import io.openware.common.media.infra.persistence.media.mapper.MediaUploadPartMapper;
import io.openware.common.media.infra.persistence.media.po.MediaUploadSessionPo;
import io.openware.common.media.infra.persistence.media.po.MediaUploadPartPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaMultipartUploadService {
  private static final long PART_SIZE = 5L * 1024 * 1024;
  private final MediaProperties properties;
  private final MediaUploadSessionService sessions;
  private final MediaUploadSessionMapper sessionMapper;
  private final MediaUploadPartMapper partMapper;
  private final MediaStoragePort storage;

  public Map<String, Object> create(long ownerId, String idempotencyKey,
      MediaUploadSessionService.CreateUploadSession request) {
    if (request.size() <= properties.multipartThresholdBytes()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "文件大小未达到分片上传阈值");
    }
    Map<String, Object> created = sessions.create(ownerId, idempotencyKey, request);
    String sessionId = (String) created.get("uploadSessionId");
    int count = Math.toIntExact((request.size() + PART_SIZE - 1) / PART_SIZE);
    if (count > 1000) throw new ApiException(HttpStatusCodes.BAD_REQUEST, "分片数量超出限制");
    MediaUploadSessionPo session = sessionMapper.selectById(sessionId);
    if (session.getPartCount() == null) {
      session.setPartSizeBytes(PART_SIZE); session.setPartCount(count);
      session.setStorageUploadId(storage.initiateMultipartUpload(session.getBucketName(), session.getTemporaryObjectKey(),
          session.getContentType())); session.setUpdatedBy(ownerId);
      session.setUpdatedAt(LocalDateTime.now()); sessionMapper.updateById(session);
      for (int number = 1; number <= count; number++) {
        MediaUploadPartPo part = new MediaUploadPartPo();
        part.setUploadSessionId(sessionId); part.setPartNumber(number); part.setStatus("PENDING");
        part.setCreatedAt(LocalDateTime.now()); part.setUpdatedAt(LocalDateTime.now()); partMapper.insert(part);
      }
    }
    return multipartCreated(session);
  }

  public Map<String, Object> signatures(long ownerId, String sessionId, List<Integer> partNumbers) {
    MediaUploadSessionPo session = requireUploading(ownerId, sessionId);
    validateParts(session, partNumbers);
    return Map.of("partUrls", partNumbers.stream().distinct().sorted().map(number -> Map.of("partNumber", number,
        "uploadUrl", storage.multipartPartUploadUrl(session.getBucketName(), session.getTemporaryObjectKey(),
            session.getStorageUploadId(), number, session.getContentType()))).toList());
  }

  public Map<String, Object> status(long ownerId, String sessionId) {
    MediaUploadSessionPo session = requireUploading(ownerId, sessionId);
    List<UploadedPart> uploaded = partMapper.selectList(Wrappers.<MediaUploadPartPo>lambdaQuery()
        .eq(MediaUploadPartPo::getUploadSessionId, sessionId).eq(MediaUploadPartPo::getStatus, "UPLOADED"))
        .stream().map(part -> new UploadedPart(part.getPartNumber(), part.getEtag()))
        .sorted(java.util.Comparator.comparingInt(UploadedPart::partNumber)).toList();
    return Map.of("uploadSessionId", sessionId, "objectId", session.getObjectId(), "status", "uploading",
        "partSize", session.getPartSizeBytes(), "partCount", session.getPartCount(), "uploadedParts", uploaded,
        "expiresAt", session.getExpiresAt().toString());
  }

  @Transactional
  public void confirmPart(long ownerId, String sessionId, int partNumber, PartConfirmation request) {
    MediaUploadSessionPo session = requireUploading(ownerId, sessionId);
    validateParts(session, List.of(partNumber));
    if (request == null || request.etag() == null || request.etag().isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "缺少分片 ETag");
    }
    MediaUploadPartPo part = partMapper.selectOne(Wrappers.<MediaUploadPartPo>lambdaQuery()
        .eq(MediaUploadPartPo::getUploadSessionId, sessionId).eq(MediaUploadPartPo::getPartNumber, partNumber));
    part.setSizeBytes(expectedPartSize(session, partNumber)); part.setEtag(request.etag().trim()); part.setStatus("UPLOADED"); part.setUpdatedAt(LocalDateTime.now()); partMapper.update(part,
        Wrappers.<MediaUploadPartPo>lambdaUpdate().eq(MediaUploadPartPo::getUploadSessionId, sessionId)
            .eq(MediaUploadPartPo::getPartNumber, partNumber));
  }

  @Transactional
  public Map<String, Object> complete(long ownerId, String sessionId, CompleteMultipartUpload request) {
    MediaUploadSessionPo session = requireUploading(ownerId, sessionId);
    List<Integer> partNumbers = request.parts() == null ? List.of() : request.parts().stream()
        .map(UploadedPart::partNumber).toList();
    validateParts(session, partNumbers);
    List<MediaUploadPartPo> uploadedParts = partMapper.selectList(Wrappers.<MediaUploadPartPo>lambdaQuery()
        .eq(MediaUploadPartPo::getUploadSessionId, sessionId).eq(MediaUploadPartPo::getStatus, "UPLOADED"))
        .stream().toList();
    long uploaded = uploadedParts.stream().mapToLong(MediaUploadPartPo::getSizeBytes).sum();
    if (uploaded != session.getSizeBytes() || request.size() != session.getSizeBytes()
        || !request.sha256().equals(session.getChecksumSha256()) || uploadedParts.size() != session.getPartCount()
        || request.parts().stream().anyMatch(item -> uploadedParts.stream().noneMatch(part ->
            part.getPartNumber().equals(item.partNumber()) && part.getEtag().equals(item.etag())))) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "分片信息与上传会话不一致");
    }
    storage.completeMultipartUpload(session.getBucketName(), session.getTemporaryObjectKey(), session.getStorageUploadId(),
        request.parts().stream().sorted(java.util.Comparator.comparingInt(UploadedPart::partNumber))
            .map(part -> new MediaStoragePort.CompletedPart(part.partNumber(), part.etag())).toList());
    return sessions.complete(ownerId, sessionId, new MediaUploadSessionService.CompleteUploadSession(request.size(), request.sha256()));
  }

  @Transactional
  public void cancel(long ownerId, String sessionId) {
    MediaUploadSessionPo session = requireUploading(ownerId, sessionId);
    storage.abortMultipartUpload(session.getBucketName(), session.getTemporaryObjectKey(), session.getStorageUploadId());
    sessions.cancel(ownerId, sessionId);
  }

  private MediaUploadSessionPo requireUploading(long ownerId, String sessionId) {
    MediaUploadSessionPo session = sessionMapper.selectOne(Wrappers.<MediaUploadSessionPo>lambdaQuery()
        .eq(MediaUploadSessionPo::getUploadSessionId, sessionId).eq(MediaUploadSessionPo::getOwnerId, ownerId));
    if (session == null) throw new ApiException(HttpStatusCodes.NOT_FOUND, "上传会话不存在");
    if (!"UPLOADING".equals(session.getStatus()) || session.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new ApiException(HttpStatusCodes.CONFLICT, "上传会话不可用");
    }
    return session;
  }

  private Map<String, Object> multipartCreated(MediaUploadSessionPo session) {
    return Map.of("uploadSessionId", session.getUploadSessionId(), "objectId", session.getObjectId(),
        "partSize", session.getPartSizeBytes(), "partCount", session.getPartCount(), "expiresAt", session.getExpiresAt().toString());
  }

  private void validateParts(MediaUploadSessionPo session, List<Integer> partNumbers) {
    if (partNumbers == null || partNumbers.isEmpty() || partNumbers.stream().distinct().count() != partNumbers.size()
        || partNumbers.stream().anyMatch(number -> number == null || number < 1 || number > session.getPartCount())) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "分片参数不合法");
    }
  }

  private long expectedPartSize(MediaUploadSessionPo session, int partNumber) {
    return partNumber == session.getPartCount() ? session.getSizeBytes() - session.getPartSizeBytes() * (partNumber - 1)
        : session.getPartSizeBytes();
  }

  public record PartNumbers(List<Integer> partNumbers) { }
  public record PartConfirmation(String etag) { }
  public record UploadedPart(int partNumber, String etag) { }
  public record CompleteMultipartUpload(List<UploadedPart> parts, long size, String sha256) { }
}
