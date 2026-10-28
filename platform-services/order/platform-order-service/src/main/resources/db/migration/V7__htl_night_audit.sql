-- H4 夜审：按营业日固化在住/待退房状态，记录房费汇总。
CREATE TABLE IF NOT EXISTS `htl_night_audit` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `business_date` date NOT NULL,
  `due_out_count` int NOT NULL DEFAULT 0,
  `in_house_count` int NOT NULL DEFAULT 0,
  `room_charge_minor` bigint NOT NULL DEFAULT 0,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `status` varchar(24) NOT NULL DEFAULT 'COMPLETED',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_night_audit_day` (`tenant_id`, `store_id`, `business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店夜审';
