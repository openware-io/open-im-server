package io.openware.common.media.infra.persistence.media.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("support_media_upload_part")
public class MediaUploadPartPo {
  @TableId(value = "part_id", type = IdType.AUTO) private Long partId;
  @TableField("upload_session_id") private String uploadSessionId;
  @TableField("part_number") private Integer partNumber;
  private String etag;
  @TableField("size_bytes") private Long sizeBytes;
  private String status;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
