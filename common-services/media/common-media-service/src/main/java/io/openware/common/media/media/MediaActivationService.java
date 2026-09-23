package io.openware.common.media.media;

import io.openware.common.media.infra.persistence.media.mapper.MediaObjectMapper;
import io.openware.common.media.infra.persistence.media.mapper.MediaUploadSessionMapper;
import io.openware.common.media.infra.persistence.media.po.MediaObjectPo;
import io.openware.common.media.infra.persistence.media.po.MediaUploadSessionPo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
public class MediaActivationService {
  private final MediaStoragePort storage;
  private final MediaObjectMapper objectMapper;
  private final MediaUploadSessionMapper sessionMapper;
  private final MediaContentValidator validator;
  private final MediaAuditService audit;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void activate(MediaUploadCompletedEvent event) {
    MediaUploadSessionPo session = sessionMapper.selectById(event.uploadSessionId());
    if (session == null) return;
    MediaObjectPo object = objectMapper.selectById(session.getObjectId());
    if (object == null || !"COMPLETED".equals(session.getStatus()) || !"UPLOADED".equals(object.getStatus())) return;
    LocalDateTime now = LocalDateTime.now();
    if (!session.getChecksumSha256().equals(storage.sha256(session.getBucketName(), session.getTemporaryObjectKey()))
        || !storage.matchesContentSignature(session.getBucketName(), session.getTemporaryObjectKey(), object.getContentType())) {
      reject(object, session, now, "Hash or content signature validation failed");
      return;
    }
    MediaContentValidator.ValidationResult result = validator.validate(object);
    if (!result.accepted()) {
      reject(object, session, now, result.reason());
      return;
    }
    String targetKey = objectKey(object);
    storage.move(session.getBucketName(), session.getTemporaryObjectKey(), targetKey);
    object.setObjectKey(targetKey); object.setStatus("ACTIVE"); object.setExpiresAt(null);
    object.setActivatedAt(now); object.setUpdatedBy(0L); object.setUpdatedAt(now); objectMapper.updateById(object);
    audit.record(object.getObjectId(), "ACTIVATED", 0L, "Media object validated and activated");
  }

  private void reject(MediaObjectPo object, MediaUploadSessionPo session, LocalDateTime now, String reason) {
    object.setStatus("REJECTED"); object.setUpdatedBy(0L); object.setUpdatedAt(now); objectMapper.updateById(object);
    storage.deleteObject(session.getBucketName(), session.getTemporaryObjectKey());
    audit.record(object.getObjectId(), "REJECTED", 0L, reason);
  }

  private String objectKey(MediaObjectPo object) {
    String date = LocalDate.now().toString().replace('-', '/');
    String folder = switch (object.getScope()) {
      case "avatar" -> "im/avatar/" + object.getOwnerId();
      case "reservation" -> "reservation/voucher/" + date;
      default -> object.getScope() + "/" + object.getMediaKind() + "/" + date;
    };
    return folder + "/" + object.getObjectId() + "." + extension(object.getOriginalFileName(), object.getContentType());
  }

  private String extension(String originalFileName, String contentType) {
    String fromName = extensionFromFileName(originalFileName);
    return fromName != null ? fromName : extensionFromContentType(contentType);
  }

  private String extensionFromFileName(String originalFileName) {
    if (originalFileName == null || originalFileName.isBlank()) return null;
    int dot = originalFileName.lastIndexOf('.');
    if (dot < 0 || dot == originalFileName.length() - 1) return null;
    String extension = originalFileName.substring(dot + 1);
    return extension.matches("[a-zA-Z0-9]{1,10}") ? extension : null;
  }

  private String extensionFromContentType(String contentType) {
    String extension = contentType.substring(contentType.indexOf('/') + 1);
    return "jpeg".equals(extension) ? "jpg" : extension;
  }
}
