-- 用户设备公钥（E2EE 设备身份）基线

CREATE TABLE `user_device_key`
(
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '设备密钥标识',
  `user_id`     bigint unsigned NOT NULL COMMENT '所属用户标识',
  `device_id`   varchar(128) NOT NULL COMMENT '设备标识',
  `public_key`  varchar(512) NOT NULL COMMENT '设备公钥（客户端生成，服务端仅存储与转发）',
  `status`      varchar(32) NOT NULL DEFAULT 'active' COMMENT '设备密钥状态',
  `created_by`  bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at`  datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by`  bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at`  datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device_key_user_device` (`user_id`, `device_id`) COMMENT '用户设备唯一约束',
  KEY `idx_user_device_key_user_id` (`user_id`) COMMENT '按用户查询设备公钥'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户设备公钥权威表';
