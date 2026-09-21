package com.gvchat.common.media.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaObjectMapper;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaUploadSessionMapper;
import com.gvchat.common.media.infra.persistence.media.po.MediaObjectPo;
import com.gvchat.common.media.infra.persistence.media.po.MediaUploadSessionPo;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MediaExpiryCleanupJob {
  private final MediaUploadSessionMapper sessionMapper;
  private final MediaObjectMapper objectMapper;
  private final MediaStoragePort storage;

  @Scheduled(fixedDelayString = "${media.cleanup-delay-ms:60000}")
  @Transactional
  public void expireUploadingSessions() {
    LocalDateTime now = LocalDateTime.now();
    for (MediaUploadSessionPo session : sessionMapper.selectList(Wrappers.<MediaUploadSessionPo>lambdaQuery()
        .eq(MediaUploadSessionPo::getStatus, "UPLOADING").lt(MediaUploadSessionPo::getExpiresAt, now))) {
      storage.deleteObject(session.getBucketName(), session.getTemporaryObjectKey());
      if (session.getPartCount() != null) {
        for (int partNumber = 1; partNumber <= session.getPartCount(); partNumber++) {
          storage.deleteObject(session.getBucketName(), "temp/upload/" + session.getUploadSessionId()
              + "/parts/" + String.format("%05d", partNumber));
        }
      }
      session.setStatus("EXPIRED"); session.setUpdatedBy(0L); session.setUpdatedAt(now); sessionMapper.updateById(session);
      MediaObjectPo object = objectMapper.selectById(session.getObjectId());
      if (object != null && "PENDING".equals(object.getStatus())) {
        object.setStatus("DELETED"); object.setDeletedAt(now); object.setUpdatedBy(0L); object.setUpdatedAt(now);
        objectMapper.updateById(object);
      }
    }
  }
}
