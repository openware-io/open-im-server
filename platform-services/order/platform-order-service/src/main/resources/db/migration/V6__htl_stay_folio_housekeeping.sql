-- H3 在住、账单、押金/结算与客房清扫。
CREATE TABLE IF NOT EXISTS `htl_stay` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `booking_id` bigint unsigned NOT NULL,
  `room_id` bigint unsigned NOT NULL,
  `room_no` varchar(32) NOT NULL,
  `check_in_at` datetime(3) NOT NULL,
  `check_out_at` datetime(3) NULL,
  `status` varchar(24) NOT NULL DEFAULT 'IN_HOUSE',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_stay_booking` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店在住';

CREATE TABLE IF NOT EXISTS `htl_folio` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `booking_id` bigint unsigned NOT NULL,
  `stay_id` bigint unsigned NULL,
  `status` varchar(24) NOT NULL DEFAULT 'OPEN',
  `currency` char(3) NOT NULL,
  `charge_minor` bigint NOT NULL DEFAULT 0,
  `paid_minor` bigint NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_folio_booking` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店账单';

CREATE TABLE IF NOT EXISTS `htl_folio_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `folio_id` bigint unsigned NOT NULL,
  `type` varchar(24) NOT NULL,
  `description` varchar(255) NULL,
  `amount_minor` bigint NOT NULL,
  `currency` char(3) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_htl_folio_item` (`folio_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店账单项';

CREATE TABLE IF NOT EXISTS `htl_housekeeping_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `room_id` bigint unsigned NOT NULL,
  `room_no` varchar(32) NOT NULL,
  `booking_id` bigint unsigned NULL,
  `status` varchar(24) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_htl_housekeeping_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客房清扫任务';
