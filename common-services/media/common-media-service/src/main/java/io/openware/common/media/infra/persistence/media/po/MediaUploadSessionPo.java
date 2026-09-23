package io.openware.common.media.infra.persistence.media.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("support_media_upload_session")
public class MediaUploadSessionPo {
  @TableId("upload_session_id") private String uploadSessionId;
  @TableField("object_id") private String objectId;
  @TableField("owner_id") private Long ownerId;
  @TableField("idempotency_key") private String idempotencyKey;
  @TableField("request_digest") private String requestDigest;
  private String provider;
  @TableField("bucket_name") private String bucketName;
  @TableField("temporary_object_key") private String temporaryObjectKey;
  private String scope;
  @TableField("media_kind") private String mediaKind;
  @TableField("content_type") private String contentType;
  @TableField("size_bytes") private Long sizeBytes;
  @TableField("checksum_sha256") private String checksumSha256;
  @TableField("storage_upload_id") private String storageUploadId;
  @TableField("part_size_bytes") private Long partSizeBytes;
  @TableField("part_count") private Integer partCount;
  private String status;
  @TableField("expires_at") private LocalDateTime expiresAt;
  @TableField("completed_at") private LocalDateTime completedAt;
  @TableField("created_by") private Long createdBy;
  @TableField("created_at") private LocalDateTime createdAt;
  @TableField("updated_by") private Long updatedBy;
  @TableField("updated_at") private LocalDateTime updatedAt;
}
