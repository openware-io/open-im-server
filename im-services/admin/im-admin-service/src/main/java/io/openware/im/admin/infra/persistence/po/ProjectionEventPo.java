package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_projection_event")
public class ProjectionEventPo {
  // 主键改为自增 `id`（`event_id` 降为唯一键 uk_adm_projection_event_event_id，幂等语义不变）。
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("event_id")
  private String eventId;
  private String eventType;
  // 原 `processed_at` 列已按审计列口径改名为 `created_at`。
  @TableField("created_at")
  private LocalDateTime processedAt;
}
