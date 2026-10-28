-- H2 库存占用账本：hold 与 booking_night 一一对应，确认时转 sold，取消时释放。
CREATE TABLE IF NOT EXISTS `htl_inventory_hold` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `booking_id` bigint unsigned NOT NULL,
  `room_type_id` bigint unsigned NOT NULL,
  `stay_date` date NOT NULL,
  `rooms` int NOT NULL DEFAULT 1,
  `status` varchar(24) NOT NULL DEFAULT 'HELD',
  `expires_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_hold_booking_night` (`booking_id`, `stay_date`),
  KEY `idx_htl_hold_expiry` (`status`, `expires_at`),
  KEY `idx_htl_hold_room_date` (`room_type_id`, `stay_date`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店库存占用';
