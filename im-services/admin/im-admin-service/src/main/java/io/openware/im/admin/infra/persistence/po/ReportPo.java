package io.openware.im.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.openware.common.enums.ReportStatus;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("adm_report")
public class ReportPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("reporter_id")
  private Long reporterId;
  @TableField("target_id")
  private Long targetId;
  private String reason;
  private String description;
  private String evidence;
  private ReportStatus status;
  @TableField("handled_by")
  private Long handledBy;
  @TableField("handle_remark")
  private String handleRemark;
  @TableField("handled_at")
  private LocalDateTime handledAt;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
