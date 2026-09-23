package io.openware.common.media.infra.persistence.media.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("support_media_audit_event")
public class MediaAuditEventPo {
  @TableId(value = "event_id", type = IdType.AUTO) private Long eventId;
  @TableField("object_id") private String objectId;
  @TableField("event_type") private String eventType;
  @TableField("actor_id") private Long actorId;
  private String detail;
  @TableField("created_at") private LocalDateTime createdAt;
}
