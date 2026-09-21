-- 邮件服务基线表：渠道配置 / 模板 / 发送日志
CREATE TABLE mail_channel_config (
  id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id bigint unsigned NOT NULL COMMENT '租户ID',
  host varchar(255) NOT NULL COMMENT 'SMTP 主机',
  port int NOT NULL COMMENT 'SMTP 端口 25/465/587',
  username varchar(255) NOT NULL COMMENT 'SMTP 账号',
  password varchar(512) NOT NULL COMMENT 'SMTP 密码，平台加密托管占位，不落明文',
  from_address varchar(255) NULL COMMENT '发件人地址',
  enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '启用开关',
  created_at datetime(3) NOT NULL COMMENT '创建时间',
  updated_at datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_mail_channel_tenant_host (tenant_id, host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮件渠道配置';

CREATE TABLE mail_template (
  id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID 0=平台',
  code varchar(64) NOT NULL COMMENT '模板 code',
  subject varchar(255) NOT NULL COMMENT '邮件主题',
  content text NOT NULL COMMENT '邮件正文',
  approval_status varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '审批状态 PENDING/APPROVED/REJECTED',
  created_at datetime(3) NOT NULL COMMENT '创建时间',
  updated_at datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_mail_template_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮件模板';

CREATE TABLE mail_send_log (
  id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id bigint unsigned NOT NULL COMMENT '租户ID',
  recipient_digest varchar(64) NOT NULL COMMENT '收件人 SHA-256 摘要，不落明文',
  recipient_masked varchar(128) NOT NULL COMMENT '收件人脱敏展示',
  template_code varchar(64) NOT NULL COMMENT '模板 code',
  subject varchar(255) NULL COMMENT '邮件主题快照',
  status varchar(32) NOT NULL COMMENT '状态 PENDING/SUCCESS/FAILED',
  retry_count int NOT NULL DEFAULT 0 COMMENT '重试次数',
  provider_message_id varchar(128) NULL COMMENT '服务商发送流水',
  raw_response varchar(2048) NULL COMMENT '服务商返回原文',
  idempotency_key varchar(128) NOT NULL COMMENT '幂等键',
  created_at datetime(3) NOT NULL COMMENT '创建时间',
  updated_at datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_mail_send_idempotency (tenant_id, idempotency_key),
  KEY idx_mail_send_query (tenant_id, template_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮件发送日志';
