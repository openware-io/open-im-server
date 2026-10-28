-- H1 酒店主数据与库存模型。酒店容量归酒店核心，不进入 KTV 资源表。
CREATE TABLE IF NOT EXISTS `htl_hotel` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `name` varchar(128) NOT NULL,
  `address` varchar(255) NULL,
  `timezone` varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
  `check_in_time` varchar(8) NOT NULL DEFAULT '14:00',
  `check_out_time` varchar(8) NOT NULL DEFAULT '12:00',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_hotel_store` (`tenant_id`, `store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店资料';

CREATE TABLE IF NOT EXISTS `htl_room_type` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `hotel_id` bigint unsigned NOT NULL,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `capacity` int NOT NULL DEFAULT 2,
  `bed_type` varchar(64) NULL,
  `area_sqm` decimal(8,2) NULL,
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_room_type_code` (`tenant_id`, `store_id`, `code`),
  KEY `idx_htl_room_type_hotel` (`tenant_id`, `store_id`, `hotel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房型';

CREATE TABLE IF NOT EXISTS `htl_room` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `hotel_id` bigint unsigned NOT NULL,
  `room_type_id` bigint unsigned NOT NULL,
  `room_no` varchar(32) NOT NULL,
  `floor` varchar(16) NULL,
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_room_no` (`tenant_id`, `store_id`, `room_no`),
  KEY `idx_htl_room_type` (`tenant_id`, `store_id`, `room_type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物理房间';

CREATE TABLE IF NOT EXISTS `htl_rate_plan` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `hotel_id` bigint unsigned NOT NULL,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `base_price_minor` bigint NOT NULL DEFAULT 0,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE',
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_rate_plan_code` (`tenant_id`, `store_id`, `code`),
  KEY `idx_htl_rate_plan_hotel` (`tenant_id`, `store_id`, `hotel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房价计划';

CREATE TABLE IF NOT EXISTS `htl_daily_rate` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `room_type_id` bigint unsigned NOT NULL,
  `rate_plan_id` bigint unsigned NOT NULL,
  `stay_date` date NOT NULL,
  `price_minor` bigint NOT NULL,
  `currency` char(3) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_daily_rate` (`room_type_id`, `rate_plan_id`, `stay_date`),
  KEY `idx_htl_daily_rate_store_date` (`tenant_id`, `store_id`, `stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日房价';

CREATE TABLE IF NOT EXISTS `htl_inventory_calendar` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `room_type_id` bigint unsigned NOT NULL,
  `stay_date` date NOT NULL,
  `total` int NOT NULL DEFAULT 0,
  `sold` int NOT NULL DEFAULT 0,
  `held` int NOT NULL DEFAULT 0,
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_inventory_day` (`room_type_id`, `stay_date`),
  KEY `idx_htl_inventory_store_date` (`tenant_id`, `store_id`, `stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='酒店每日库存';

ALTER TABLE `htl_booking`
  ADD COLUMN `hotel_id` bigint unsigned NULL AFTER `store_id`,
  ADD COLUMN `room_type_id` bigint unsigned NULL AFTER `hotel_id`,
  ADD COLUMN `rate_plan_id` bigint unsigned NULL AFTER `room_type_id`,
  ADD COLUMN `guest_phone` varchar(32) NULL AFTER `guest_name`,
  ADD COLUMN `rooms` int NOT NULL DEFAULT 1 AFTER `room_no`,
  ADD COLUMN `nights` int NOT NULL DEFAULT 1 AFTER `rooms`,
  ADD COLUMN `source` varchar(24) NOT NULL DEFAULT 'WEB' AFTER `currency`,
  ADD COLUMN `hold_expires_at` datetime(3) NULL AFTER `source`,
  ADD KEY `idx_htl_booking_hotel_dates` (`tenant_id`, `store_id`, `hotel_id`, `check_in_date`, `check_out_date`);

CREATE TABLE IF NOT EXISTS `htl_booking_night` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL,
  `store_id` bigint unsigned NOT NULL,
  `booking_id` bigint unsigned NOT NULL,
  `stay_date` date NOT NULL,
  `price_minor` bigint NOT NULL,
  `currency` char(3) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'HELD',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_htl_booking_night` (`booking_id`, `stay_date`),
  KEY `idx_htl_booking_night_store_date` (`tenant_id`, `store_id`, `stay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预订按夜价格';
