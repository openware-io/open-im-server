-- 代币（Coin）：虚拟代币账户与流水（对齐积分，余额用 bigint 避免 42.9 亿上限）
CREATE TABLE `user_coin_account`
(
  `id`         bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `balance`    bigint unsigned NOT NULL DEFAULT '0' COMMENT '当前可用代币',
  `created_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coin_account_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户代币账户';

CREATE TABLE `user_coin_ledger`
(
  `id`                bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`           bigint unsigned NOT NULL COMMENT '所属用户 ID',
  `entry_type`        enum('credit','debit') NOT NULL COMMENT '代币变动类型',
  `amount`            bigint unsigned NOT NULL COMMENT '变动数量',
  `balance_after`     bigint unsigned NOT NULL COMMENT '变动后余额',
  `reason`            varchar(512) NOT NULL COMMENT '变动原因',
  `business_type`     varchar(64) DEFAULT NULL COMMENT '业务类型',
  `business_order_no` varchar(64) DEFAULT NULL COMMENT '业务单号',
  `command_id`        varchar(64) DEFAULT NULL COMMENT '代币操作幂等键',
  `operator_user_id`  bigint unsigned DEFAULT NULL COMMENT '操作人 ID',
  `created_by`        bigint unsigned NOT NULL DEFAULT '0' COMMENT '创建人 ID，0 表示系统',
  `created_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_by`        bigint unsigned NOT NULL DEFAULT '0' COMMENT '更新人 ID，0 表示系统',
  `updated_at`        datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_coin_ledger_user_created` (`user_id`, `created_at`),
  KEY `idx_user_coin_ledger_business` (`business_type`, `business_order_no`),
  UNIQUE KEY `uk_user_coin_ledger_command_id` (`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户代币流水';
