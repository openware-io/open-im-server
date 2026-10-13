-- 资源服务基线表：经营资源 / 资源排班 / 资源占用 / 资源事件 Outbox / 包厢房型字典
-- 仅适用于未执行该基线的新建数据库：本文件是原 V1~V8 的累积终态，数据库清空后由 Flyway 从 V1 一次性重建。
-- 合并来源：V2 资源事件 Outbox（补齐 created_by / updated_by 两个审计列）、V3 A380 包厢种子数据、
--           V4 包厢清洁状态、V5 资源图片与描述、V6 区域与房型字典、V7 公共媒体 URL 前缀规范化、V8 房型图片。
-- 口径依据：SAAS_PLATFORM_04_DATA.md §5；SAAS_PLATFORM_06_TECHNICAL.md §8。

-- 资源：包厢 / 服务人员等经营资源。resource_type 首发 KTV_ROOM 包厢、KTV_SERVER 服务人员，
-- HOTEL_ROOM/SPA_ROOM/THERAPIST 为后续业态；唯一键 (tenant_id, store_id, resource_type, resource_code)。
CREATE TABLE `res_resource` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '资源ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型 KTV_ROOM/KTV_SERVER（HOTEL_ROOM/SPA_ROOM/THERAPIST 后续）',
  `resource_code` varchar(64) NOT NULL COMMENT '资源编码',
  `name` varchar(128) NOT NULL COMMENT '资源名称',
  `area_name` varchar(64) NULL COMMENT '所属区域（自由文本，如「三楼 A 区」）',
  `parent_id` bigint unsigned NULL COMMENT '父资源',
  `capacity` int NULL COMMENT '容量',
  `room_type_id` bigint unsigned NULL COMMENT '房型ID（res_room_type.id，同门店）',
  `status` varchar(24) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED/MAINTENANCE',
  `cleaning_status` varchar(24) NOT NULL DEFAULT 'IDLE' COMMENT '清洁状态 IDLE/CLEANING',
  `cleaning_started_at` datetime(3) NULL COMMENT '进入清洁中时间',
  `attributes_json` json NULL COMMENT '扩展属性',
  `image_urls` json NULL COMMENT '资源图片URL列表(JSON数组，最多9张)',
  `main_image_url` varchar(512) NULL COMMENT '资源主图URL(必须属于图片列表)',
  `description` varchar(255) NULL COMMENT '资源描述',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_res_resource_tenant_store_type_code` (`tenant_id`, `store_id`, `resource_type`, `resource_code`),
  KEY `idx_res_resource_room_type` (`tenant_id`, `store_id`, `room_type_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='经营资源';

-- 资源排班：资源可被占用 / 可预约的时间窗口，索引对应「按租户 + 资源 + 时间区间」的冲突与看板查询路径。
CREATE TABLE `res_schedule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '排班ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `resource_id` bigint unsigned NOT NULL COMMENT '资源ID',
  `start_at` datetime(3) NOT NULL COMMENT '排班开始时间',
  `end_at` datetime(3) NOT NULL COMMENT '排班结束时间',
  `schedule_type` varchar(32) NOT NULL COMMENT '排班类型',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `source` varchar(32) NULL COMMENT '来源',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_res_schedule_resource_time` (`tenant_id`, `resource_id`, `start_at`, `end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源排班';

-- 资源占用：事务内通过锁定同一资源的有效占用记录完成冲突判断，索引带 status 覆盖该判定路径。
CREATE TABLE `res_occupation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '占用记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `resource_id` bigint unsigned NOT NULL COMMENT '资源ID',
  `source_type` varchar(32) NOT NULL COMMENT '来源类型 ORDER/RESERVATION',
  `source_id` bigint unsigned NOT NULL COMMENT '来源ID',
  `start_at` datetime(3) NOT NULL COMMENT '占用开始时间',
  `end_at` datetime(3) NOT NULL COMMENT '占用结束时间',
  `status` varchar(24) NOT NULL DEFAULT 'HELD' COMMENT 'HELD/RESERVED/IN_USE/RELEASED/CANCELLED',
  `hold_expires_at` datetime(3) NULL COMMENT 'HELD 过期时间',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_res_occupation_resource_time_status` (`tenant_id`, `resource_id`, `start_at`, `end_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源占用';

-- 资源事件 Outbox（业务事务与 Outbox 同库提交，Relay 按 PENDING→PUBLISHED/FAILED 投递 RocketMQ）
-- 口径依据：SAAS_PLATFORM_06_TECHNICAL.md §8；事件载荷最小化，带租户上下文，不含敏感原文。
CREATE TABLE `res_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Outbox记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 resource.occupation.released)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 occupation)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `payload_json` json NOT NULL COMMENT '事件载荷(最小化，含租户上下文)',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime(3) NULL COMMENT '下次重试时间(指数退避)',
  `published_at` datetime(3) NULL COMMENT '投递成功时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_res_event_outbox_event` (`event_id`),
  KEY `idx_res_event_outbox_pending` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源事件 Outbox';

-- 包厢房型字典：门店可维护房型（小包/中包/大包/VIP），包厢引用房型。
-- 房型同时承载「房型单价」：计价方案 tnt_pricing_plan 按 (tenant, store, resource_type) 唯一，只能表达门店级单价；
--   要做到「资源有 room_type_id 时按房型单价计费、缺该房型回退门店级单价」，房型级单价必须有可维护的权威数据，
--   因此落在房型字典上（unit_price 房费单价 / server_unit_price 服务人员单价，均为「最小货币单位/计费单位」，
--   NULL 或 <=0 表示回退门店级单价）。金额一律最小货币单位整数，与 tnt_pricing_plan.price_per_unit 同口径。
-- 名称门店内唯一：后台按名称展示与选择，重名会造成「选哪个房型」歧义（接口先查再插给 409，唯一键兜底并发）。
CREATE TABLE `res_room_type` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '房型ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `code` varchar(32) NOT NULL COMMENT '房型编码（门店内唯一）',
  `name` varchar(64) NOT NULL COMMENT '房型名称（门店内唯一）',
  `capacity` int NULL COMMENT '标准容纳人数',
  `image_urls` json NULL COMMENT '房型图片URL列表(JSON数组，最多9张)',
  `main_image_url` varchar(512) NULL COMMENT '房型主图URL(必须属于图片列表)',
  `unit_price` bigint NULL COMMENT '房型单价（房费，最小货币单位/计费单位；NULL 回退门店级单价）',
  `server_unit_price` bigint NULL COMMENT '房型服务人员单价（最小货币单位/计费单位；NULL 回退门店级服务人员单价）',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_res_room_type_tenant_store_code` (`tenant_id`, `store_id`, `code`),
  UNIQUE KEY `uk_res_room_type_tenant_store_name` (`tenant_id`, `store_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='包厢房型字典';

-- A380 KTV 包厢资源（tenant_id=100, store_id=100）；幂等：重复执行只刷新名称，不新增行。
INSERT INTO `res_resource` (`id`, `tenant_id`, `store_id`, `resource_type`, `resource_code`, `name`, `capacity`, `status`, `created_at`, `updated_at`) VALUES
(1001, 100, 100, 'KTV_ROOM', 'K01', '小包 K01', 4, 'ENABLED', NOW(3), NOW(3)),
(1002, 100, 100, 'KTV_ROOM', 'K02', '小包 K02', 4, 'ENABLED', NOW(3), NOW(3)),
(1003, 100, 100, 'KTV_ROOM', 'K08', '中包 K08', 8, 'ENABLED', NOW(3), NOW(3)),
(1004, 100, 100, 'KTV_ROOM', 'K09', '中包 K09', 8, 'ENABLED', NOW(3), NOW(3)),
(1005, 100, 100, 'KTV_ROOM', 'K12', '大包 K12', 15, 'ENABLED', NOW(3), NOW(3)),
(1006, 100, 100, 'KTV_ROOM', 'K15', '派对包 K15', 20, 'ENABLED', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `updated_at` = `updated_at`;

-- 公共媒体 URL 规范化为网关 /api 命名空间：把 res_resource 的包厢图片地址里以 /media-public/ 开头的值
-- 直接改写为 /api/v1/media-public/（与 order 服务 V21__ord_public_media_url_prefix.sql 同一规则、同一批数据）。
--
-- 背景：旧前缀 /media-public/{bucket}/{objectKey} 依赖各环境入口剥前缀，ACK 入口不剥前缀时 MinIO 会把
-- media-public 当桶名返回 403 AccessDenied；新前缀落在所有环境都路由到网关的 /api 命名空间，
-- 由网关 StripPrefix=3 统一剥成 /{bucket}/{objectKey}。
--
-- 写法与边界（只替换「值以 /media-public/ 开头」的前缀，不误伤字符串其它位置）：
--   * JSON 列 image_urls：先 CAST(col AS CHAR) 取 JSON 文本，再用 REPLACE 匹配带左引号的 '"/media-public/'，
--     即只命中数组元素字符串开头的旧前缀；出现在字符串中间或结尾的 /media-public/ 不匹配。
--   * varchar 列 main_image_url：用 LIKE '/media-public/%' 锚定行首，再 SUBSTRING 掉前 14 个字符的旧前缀。
-- 幂等：两条 UPDATE 的 WHERE 都只匹配旧前缀；改写后的值以 /api/v1/ 开头，重复执行匹配 0 行，不产生二次替换。
UPDATE `res_resource`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `res_resource`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';
