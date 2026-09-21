-- 统一审计日志（平台化改造）：在既有 iam_audit_log 上补齐「谁、在哪个租户/组织/门店、做了什么、
-- 对哪个对象、结果如何、从哪来」的全部字段与查询索引。
--
-- 口径：
--   * 审计记录只增不改、不物理删除；本迁移只做加列 + 加索引 + 放宽 tenant_id 语义；
--   * tenant_id = 0 表示平台级动作（如创建租户），租户视角查询永远不会命中它；
--   * 动作码 action 是稳定契约，action_label 是中文标签（可随产品文案演进）；
--   * 唯一键沿用 (tenant_id, idempotency_key)，写入幂等由它保证；
--   * result / operator_type 用字符串枚举，避免 MySQL ENUM 变更需要 DDL。
ALTER TABLE `iam_audit_log`
  MODIFY COLUMN `tenant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级动作',
  MODIFY COLUMN `action` varchar(128) NOT NULL COMMENT '动作稳定码 如 order.settle',
  MODIFY COLUMN `resource_type` varchar(64) NOT NULL DEFAULT '' COMMENT '资源类型 如 ord_order',
  MODIFY COLUMN `resource_id` varchar(128) NULL COMMENT '资源ID',
  MODIFY COLUMN `request_id` varchar(64) NULL COMMENT '请求ID',
  MODIFY COLUMN `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  MODIFY COLUMN `detail_json` json NULL COMMENT '详情快照（写入前脱敏）',
  MODIFY COLUMN `created_at` datetime(3) NOT NULL COMMENT '落库时间';

ALTER TABLE `iam_audit_log`
  ADD COLUMN `organization_id` bigint unsigned NULL COMMENT '组织ID' AFTER `tenant_id`,
  ADD COLUMN `store_id` bigint unsigned NULL COMMENT '门店ID' AFTER `organization_id`,
  ADD COLUMN `operator_name` varchar(128) NULL COMMENT '操作人名称快照' AFTER `operator_id`,
  ADD COLUMN `operator_account` varchar(128) NULL COMMENT '操作人账号（登录名/工号）' AFTER `operator_name`,
  ADD COLUMN `operator_type` varchar(16) NOT NULL DEFAULT 'TENANT' COMMENT '操作人类型 PLATFORM/TENANT' AFTER `operator_account`,
  ADD COLUMN `action_label` varchar(128) NOT NULL DEFAULT '' COMMENT '动作中文标签' AFTER `action`,
  ADD COLUMN `resource_name` varchar(255) NULL COMMENT '资源名称快照' AFTER `resource_id`,
  ADD COLUMN `result` varchar(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '结果 SUCCESS/FAILURE' AFTER `resource_name`,
  ADD COLUMN `error_code` varchar(64) NULL COMMENT '失败错误码' AFTER `result`,
  ADD COLUMN `ip` varchar(45) NULL COMMENT '来源IP（IPv6 兼容）' AFTER `error_code`,
  ADD COLUMN `user_agent` varchar(512) NULL COMMENT 'User-Agent（截断存储）' AFTER `ip`,
  ADD COLUMN `trace_id` varchar(64) NULL COMMENT '链路追踪ID' AFTER `request_id`,
  ADD COLUMN `source_service` varchar(64) NULL COMMENT '上报来源服务（内部鉴权来源）' AFTER `trace_id`,
  ADD COLUMN `occurred_at` datetime(3) NULL COMMENT '业务发生时间（缺省等于 created_at）' AFTER `created_at`;

-- 查询索引：租户视角按 (tenant_id, created_at)，平台视角按 created_at/action/操作人/资源。
ALTER TABLE `iam_audit_log`
  ADD KEY `idx_iam_audit_tenant_time` (`tenant_id`, `created_at`),
  ADD KEY `idx_iam_audit_operator_time` (`operator_id`, `created_at`),
  ADD KEY `idx_iam_audit_action_time` (`action`, `created_at`),
  ADD KEY `idx_iam_audit_resource` (`resource_type`, `resource_id`),
  ADD KEY `idx_iam_audit_result_time` (`result`, `created_at`),
  ADD KEY `idx_iam_audit_request` (`request_id`);

-- 历史行的 action_label 用动作码回填，保证前端列表不会出现空标签（新写入由服务端补中文标签）。
UPDATE `iam_audit_log` SET `action_label` = `action` WHERE `action_label` = '';
UPDATE `iam_audit_log` SET `occurred_at` = `created_at` WHERE `occurred_at` IS NULL;
UPDATE `iam_audit_log` SET `resource_type` = '' WHERE `resource_type` IS NULL;
