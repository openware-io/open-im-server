-- E2EE 真加密：secret_chat 增加设备公钥、握手状态与审计字段（V4 表补齐审计列）

ALTER TABLE `secret_chat`
  ADD COLUMN `user_a_public_key` varchar(512) DEFAULT NULL COMMENT '参与者A设备公钥（客户端生成，服务端仅存储转发）',
  ADD COLUMN `user_b_public_key` varchar(512) DEFAULT NULL COMMENT '参与者B设备公钥',
  ADD COLUMN `handshake_state` varchar(32) NOT NULL DEFAULT 'pending' COMMENT '握手状态(pending/ready)',
  ADD COLUMN `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  ADD COLUMN `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人';
