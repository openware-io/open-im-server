-- IAM 高风险动作在线复核（二次复核）：
-- 角色/权限变更、退款审批、密钥操作等高风险动作先落 PENDING 复核，复核通过后执行后续动作。
CREATE TABLE iam_approval (
  id              bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id       bigint unsigned NOT NULL COMMENT '租户ID',
  action_type     varchar(64)  NOT NULL COMMENT '高风险动作类型 ROLE_ASSIGN/ROLE_PERMISSION_ASSIGN/REFUND_APPROVAL/KEY_OPERATION',
  resource_type   varchar(64)  NOT NULL COMMENT '资源类型 role/user_role/refund/api_key',
  resource_id     varchar(128) NOT NULL COMMENT '资源标识',
  operator_id     bigint unsigned NOT NULL DEFAULT 0 COMMENT '发起人账号ID',
  detail_json     text NULL COMMENT '操作负载快照(JSON)',
  status          varchar(24)  NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/APPROVED/REJECTED',
  idempotency_key varchar(128) NULL COMMENT '幂等键',
  approver_id     bigint unsigned NULL COMMENT '复核人账号ID',
  review_comment  varchar(512) NULL COMMENT '复核意见',
  created_at      datetime(3) NOT NULL COMMENT '创建时间',
  updated_at      datetime(3) NOT NULL COMMENT '更新时间',
  reviewed_at     datetime(3) NULL COMMENT '复核时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_approval_idempotency (tenant_id, idempotency_key),
  KEY idx_iam_approval_tenant_status (tenant_id, status),
  KEY idx_iam_approval_tenant_action (tenant_id, action_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IAM 高风险动作在线复核';
