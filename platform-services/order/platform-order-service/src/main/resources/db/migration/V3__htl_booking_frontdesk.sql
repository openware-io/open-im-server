-- 酒店前台最小切片：只覆盖任务列表/入住/退房，用于动作级权限与工作台联调。
CREATE TABLE IF NOT EXISTS `htl_booking` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `booking_no` varchar(64) NOT NULL COMMENT '预订号',
  `guest_name` varchar(128) NOT NULL COMMENT '客人姓名',
  `room_type` varchar(128) NOT NULL COMMENT '房型名称快照',
  `room_no` varchar(32) NULL COMMENT '房号',
  `check_in_date` date NOT NULL COMMENT '入住日期',
  `check_out_date` date NOT NULL COMMENT '离店日期',
  `status` varchar(24) NOT NULL DEFAULT 'ARRIVAL' COMMENT 'ARRIVAL/IN_HOUSE/DUE_OUT/CHECKED_OUT',
  `amount_minor` bigint NOT NULL DEFAULT 0 COMMENT '应付金额（最小货币单位）',
  `currency` char(3) NOT NULL DEFAULT 'USD' COMMENT '币种',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_booking_tenant_store_no` (`tenant_id`, `store_id`, `booking_no`),
  KEY `idx_htl_booking_store_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店预订前台切片';

INSERT INTO `htl_booking`
  (`tenant_id`, `store_id`, `booking_no`, `guest_name`, `room_type`, `room_no`, `check_in_date`, `check_out_date`, `status`, `amount_minor`, `currency`, `created_at`, `updated_at`)
VALUES
  (100, 100, 'H202610220001', '王先生', '豪华大床房', '1208', CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 2 DAY), 'ARRIVAL', 73600, 'USD', NOW(3), NOW(3)),
  (100, 100, 'H202610220002', '李女士', '景观双床房', '1506', CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY), 'IN_HOUSE', 85600, 'USD', NOW(3), NOW(3)),
  (100, 100, 'H202610220003', '周女士', '豪华大床房', '0806', DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY), CURRENT_DATE, 'DUE_OUT', 36800, 'USD', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);
