-- 业务支撑域媒体数据库基线：仅适用于尚未执行此基线的新建数据库。

CREATE TABLE support_media_object (
  object_id VARCHAR(64) NOT NULL COMMENT '媒体对象标识',
  owner_id BIGINT UNSIGNED NOT NULL COMMENT '上传用户标识',
  provider VARCHAR(32) NOT NULL COMMENT '对象存储提供方标识',
  bucket_name VARCHAR(128) NOT NULL COMMENT '存储桶名称',
  object_key VARCHAR(512) NOT NULL COMMENT '对象存储定位键',
  scope VARCHAR(32) NOT NULL COMMENT '业务用途范围',
  media_kind VARCHAR(32) NOT NULL COMMENT '媒体类别',
  content_type VARCHAR(128) NOT NULL COMMENT '内容类型',
  original_file_name VARCHAR(255) NULL COMMENT '经校验的原始展示文件名',
  size_bytes BIGINT UNSIGNED NOT NULL COMMENT '文件大小字节数',
  checksum_sha256 CHAR(64) NULL COMMENT 'SHA-256完整性校验值',
  duration_ms BIGINT UNSIGNED NULL COMMENT '音视频时长毫秒数',
  width INT UNSIGNED NULL COMMENT '图像或视频宽度像素数',
  height INT UNSIGNED NULL COMMENT '图像或视频高度像素数',
  upload_session_id VARCHAR(64) NULL COMMENT '关联上传会话标识',
  status VARCHAR(16) NOT NULL COMMENT '媒体对象状态',
  expires_at DATETIME(3) NULL COMMENT '上传会话过期时间',
  activated_at DATETIME(3) NULL COMMENT '对象激活时间',
  deleted_at DATETIME(3) NULL COMMENT '逻辑删除时间',
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建操作人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (object_id),
  UNIQUE KEY uk_support_media_upload_session (upload_session_id) COMMENT '每个上传会话仅对应一个媒体对象',
  KEY idx_support_media_owner (owner_id, created_at) COMMENT '用户媒体查询',
  KEY idx_support_media_expiry (status, expires_at) COMMENT '过期媒体清理查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务支撑媒体对象权威表';

CREATE TABLE support_media_upload_session (
  upload_session_id VARCHAR(64) NOT NULL COMMENT '上传会话标识',
  object_id VARCHAR(64) NOT NULL COMMENT '媒体对象标识',
  owner_id BIGINT UNSIGNED NOT NULL COMMENT '上传用户标识',
  idempotency_key VARCHAR(64) NOT NULL COMMENT '初始化请求幂等键',
  request_digest CHAR(64) NOT NULL COMMENT '初始化请求语义摘要',
  provider VARCHAR(32) NOT NULL COMMENT '对象存储提供方标识',
  bucket_name VARCHAR(128) NOT NULL COMMENT '存储桶名称',
  temporary_object_key VARCHAR(512) NOT NULL COMMENT '临时对象存储键',
  scope VARCHAR(32) NOT NULL COMMENT '业务用途范围',
  media_kind VARCHAR(32) NOT NULL COMMENT '媒体类别',
  content_type VARCHAR(128) NOT NULL COMMENT '声明内容类型',
  size_bytes BIGINT UNSIGNED NOT NULL COMMENT '声明文件大小字节数',
  checksum_sha256 CHAR(64) NOT NULL COMMENT '声明SHA-256完整性校验值',
  storage_upload_id VARCHAR(256) NULL COMMENT '对象存储分片上传标识',
  part_size_bytes BIGINT UNSIGNED NULL COMMENT '分片大小字节数',
  part_count INT UNSIGNED NULL COMMENT '分片总数',
  status VARCHAR(16) NOT NULL COMMENT '上传会话状态',
  expires_at DATETIME(3) NOT NULL COMMENT '会话过期时间',
  completed_at DATETIME(3) NULL COMMENT '完成时间',
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建操作人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (upload_session_id),
  UNIQUE KEY uk_support_media_upload_object (object_id) COMMENT '一个媒体对象仅有一个上传会话',
  UNIQUE KEY uk_support_media_upload_idempotency (owner_id, idempotency_key) COMMENT '上传初始化请求幂等控制',
  KEY idx_support_media_upload_owner (owner_id, status, expires_at) COMMENT '用户恢复或清理上传会话查询',
  KEY idx_support_media_upload_expiry (status, expires_at) COMMENT '过期上传会话清理查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务支撑媒体直传会话权威表';

CREATE TABLE support_media_reference (
  reference_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '媒体引用标识',
  object_id VARCHAR(64) NOT NULL COMMENT '媒体对象标识',
  business_type VARCHAR(64) NOT NULL COMMENT '引用业务类型',
  business_id VARCHAR(64) NOT NULL COMMENT '引用业务实体标识',
  reference_role VARCHAR(32) NOT NULL COMMENT '引用角色标识',
  created_by BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '创建操作人',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (reference_id),
  UNIQUE KEY uk_support_media_reference_business (business_type, business_id, reference_role, object_id) COMMENT '业务媒体引用幂等控制',
  KEY idx_support_media_reference_object (object_id, created_at) COMMENT '媒体对象引用与删除校验查询',
  KEY idx_support_media_reference_business (business_type, business_id) COMMENT '业务实体媒体查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务支撑媒体通用引用关系表';

CREATE TABLE support_media_upload_part (
  part_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分片记录标识',
  upload_session_id VARCHAR(64) NOT NULL COMMENT '分片上传会话标识',
  part_number INT UNSIGNED NOT NULL COMMENT '分片序号',
  etag VARCHAR(128) NULL COMMENT '对象存储返回的分片ETag',
  size_bytes BIGINT UNSIGNED NULL COMMENT '已上传分片大小',
  status VARCHAR(16) NOT NULL COMMENT '分片状态',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间',
  PRIMARY KEY (part_id),
  UNIQUE KEY uk_support_media_part_session_number (upload_session_id, part_number) COMMENT '上传会话分片唯一约束',
  KEY idx_support_media_part_status (upload_session_id, status) COMMENT '分片恢复查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务支撑媒体分片记录表';

CREATE TABLE support_media_audit_event (
  event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '媒体审计事件标识',
  object_id VARCHAR(64) NOT NULL COMMENT '媒体对象标识',
  event_type VARCHAR(32) NOT NULL COMMENT '媒体事件类型',
  actor_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '操作用户或系统标识',
  detail VARCHAR(255) NULL COMMENT '脱敏事件详情',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (event_id),
  KEY idx_support_media_audit_object (object_id, created_at) COMMENT '媒体对象审计查询'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务支撑媒体审计事件表';
