-- 包厢「区域」与「房型字典」：
--
-- 1) 区域（area_name）：包厢管理页与 B 端房态看板要显示包厢所在区域（如「三楼 A 区」），后台前端此前
--    把该列标为「未接入」。只加一列自由文本，不拆 zone/floor：前端只有「区域」一列要展示，没有按楼层/
--    分区筛选或排序的需求，拆成两列会让同一信息出现「自由文本」与「结构化字段」两份权威并可能互相矛盾。
--
-- 2) 房型字典（res_room_type）+ res_resource.room_type_id：门店可维护房型（小包/中包/大包/VIP），包厢引用房型。
--    房型同时承载「房型单价」：计价方案 tnt_pricing_plan 按 (tenant, store, resource_type) 唯一，只能表达
--    门店级单价；要做到「资源有 room_type_id 时按房型单价计费、缺该房型回退门店级单价」，房型级单价必须
--    有可维护的权威数据，因此落在房型字典上（unit_price 房费单价 / server_unit_price 服务人员单价，
--    均为「最小货币单位/计费单位」，NULL 或 <=0 表示回退门店级单价）。
--    金额一律最小货币单位整数，与 tnt_pricing_plan.price_per_unit 同口径。
--
-- 只加列/加表，不改历史迁移、不 DROP、不动已有列；存量 res_resource 的 area_name/room_type_id 保持 NULL
-- （前端按「未设置」展示），存量包厢继续走门店级单价，计费行为不变。
CREATE TABLE `res_room_type` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '房型ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `code` varchar(32) NOT NULL COMMENT '房型编码（门店内唯一）',
  `name` varchar(64) NOT NULL COMMENT '房型名称（门店内唯一）',
  `capacity` int NULL COMMENT '标准容纳人数',
  `unit_price` bigint NULL COMMENT '房型单价（房费，最小货币单位/计费单位；NULL 回退门店级单价）',
  `server_unit_price` bigint NULL COMMENT '房型服务人员单价（最小货币单位/计费单位；NULL 回退门店级服务人员单价）',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序号',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_res_room_type_tenant_store_code` (`tenant_id`, `store_id`, `code`),
  -- 名称门店内唯一：后台按名称展示与选择，重名会造成「选哪个房型」歧义（接口先查再插给 409，唯一键兜底并发）。
  UNIQUE KEY `uk_res_room_type_tenant_store_name` (`tenant_id`, `store_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='包厢房型字典';

ALTER TABLE `res_resource`
  ADD COLUMN `area_name` varchar(64) NULL COMMENT '所属区域（自由文本，如「三楼 A 区」）' AFTER `name`,
  ADD COLUMN `room_type_id` bigint unsigned NULL COMMENT '房型ID（res_room_type.id，同门店）' AFTER `capacity`,
  ADD KEY `idx_res_resource_room_type` (`tenant_id`, `store_id`, `room_type_id`);
