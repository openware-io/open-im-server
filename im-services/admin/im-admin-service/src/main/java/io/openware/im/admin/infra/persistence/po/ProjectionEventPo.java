package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_projection_event")
public class ProjectionEventPo {
  @TableId
  private String eventId;
  private String eventType;
  private LocalDateTime processedAt;
}
