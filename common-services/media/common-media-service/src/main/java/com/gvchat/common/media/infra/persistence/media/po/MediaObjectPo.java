package com.gvchat.common.media.infra.persistence.media.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("support_media_object")
public class MediaObjectPo {
  @TableId("object_id") private String objectId;
  @TableField("owner_id") private Long ownerId;
  private String provider;
  @TableField("bucket_name") private String bucketName;
  @TableField("object_key") private String objectKey;
  private String scope;
  @TableField("media_kind") private String mediaKind;
  @TableField("content_type") private String contentType;
  @TableField("original_file_name") private String originalFileName;
  @TableField("size_bytes") private Long sizeBytes;
  @TableField("checksum_sha256") private String checksumSha256;
  @TableField("duration_ms") private Long durationMs;
  private Integer width;
  private Integer height;
  private String status;
  @TableField("upload_session_id") private String uploadSessionId;
  @TableField("expires_at") private LocalDateTime expiresAt;
  @TableField("activated_at") private LocalDateTime activatedAt;
  @TableField("deleted_at") private LocalDateTime deletedAt;
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
