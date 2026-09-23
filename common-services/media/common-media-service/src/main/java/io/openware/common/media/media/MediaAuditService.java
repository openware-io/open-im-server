package io.openware.common.media.media;

import io.openware.common.media.infra.persistence.media.mapper.MediaAuditEventMapper;
import io.openware.common.media.infra.persistence.media.po.MediaAuditEventPo;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MediaAuditService {
  public static final String ACCESS_URL_SIGNED = "ACCESS_URL_SIGNED";

  private final MediaAuditEventMapper mapper;

  public void record(String objectId, String eventType, long actorId, String detail) {
    MediaAuditEventPo event = new MediaAuditEventPo();
    event.setObjectId(objectId); event.setEventType(eventType); event.setActorId(actorId);
    event.setDetail(detail); event.setCreatedAt(LocalDateTime.now()); mapper.insert(event);
  }

  /** 记录「换取下载 URL」审计：requesterId、objectId、businessType、timestamp、action=ACCESS_URL_SIGNED。 */
  public void recordAccessUrlSigned(long requesterId, String objectId, String businessType) {
    String detail = businessType == null || businessType.isBlank() ? null : "businessType=" + businessType;
    record(objectId, ACCESS_URL_SIGNED, requesterId, detail);
  }
}
