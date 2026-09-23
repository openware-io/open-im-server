package io.openware.im.user.infra.persistence.openplatform.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("open_application")
public class OpenApplicationPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("app_id")
  private String appId;
  @TableField("app_name")
  private String appName;
  @TableField("subject_name")
  private String subjectName;
  @TableField("app_type")
  private String appType;
  @TableField("callback_url")
  private String callbackUrl;
  @TableField("app_secret_hash")
  private String appSecretHash;
  private String status;
  @TableField("reject_reason")
  private String rejectReason;
  @TableField("reviewed_by")
  private Long reviewedBy;
  @TableField("reviewed_at")
  private LocalDateTime reviewedAt;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
