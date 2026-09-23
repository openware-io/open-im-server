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
@TableName("support_media_reference")
public class MediaReferencePo {
  @TableId(value = "reference_id", type = IdType.AUTO) private Long referenceId;
  @TableField("object_id") private String objectId;
  @TableField("business_type") private String businessType;
  @TableField("business_id") private String businessId;
  @TableField("reference_role") private String referenceRole;
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
}
