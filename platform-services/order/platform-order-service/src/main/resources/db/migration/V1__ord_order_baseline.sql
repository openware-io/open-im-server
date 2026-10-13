-- 订单域（platform-order-service）统一基线。
--
-- 基线重建（批次 2）：原 V1..V32 共 32 个迁移折叠为唯一 V1，本文件独自产出最终库结构 + 最终数据状态。
--   * 建表语句取「累计最终态」：原 ALTER TABLE 新增/修改的列直接写进 CREATE TABLE，列序与累计执行结果逐列一致；
--   * 播种与数据订正语句保留在文末（原 V9 种子目录、V14/V17/V21/V22/V24/V26 的回填），谓词与顺序保持不变且幂等；
--   * 索引名统一为 <前缀>_<完整表名>_<语义>：原 idx_ord_catalog_*（表 ord_catalog_item）、
--     idx_ord_inventory_tx_* 与 uk_ord_inventory_tx_*（表 ord_inventory_transaction）、
--     idx_ord_ktv_server_*（表 ord_ktv_server_session）、uk_ord_reservation_rule_* 与 idx_ord_reservation_rule_*（表
--     ord_reservation_rule_config）、uk_ord_void_approval_* 与 idx_ord_void_approval_*（表 ord_order_void_approval）、
--     uk_ord_void_rule_* 与 idx_ord_void_rule_*（表 ord_void_rule_config）一律展开为完整表名；
--   * 补齐缺失的审计列（created_by/created_at/updated_by/updated_at，见各表注释）并给每一列都补上中文 COMMENT；
--   * 目标库 open_im，Flyway 历史表 flyway_schema_history_order；后续变更从 V2 起编号。
--
-- 注意：折叠基线必然删除 V2..V32，已存在的数据库无法再平滑升级（Flyway 会认为 V2..V32 从未执行）；
-- 这是基线重建明确接受的代价（未上线、无数据），由「清库重建」兜底，不写任何兼容/补偿语句。
--
-- 跨域说明：本文件只读写 ord_* 表。资源域（res_room_type / res_resource）、客户域（cst_customer）只在列注释里
-- 作为取值来源被提及，迁移中不读取它们的表，因此不存在跨服务 Flyway 顺序依赖。

-- ============================================================================
-- 一、表结构（累计最终态）
-- ============================================================================

CREATE TABLE `ord_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `organization_id` bigint unsigned NOT NULL COMMENT '组织ID（集团/公司层级）',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `order_no` varchar(64) NOT NULL COMMENT '订单号',
  `idempotency_key` varchar(64) NULL COMMENT '幂等键（请求头 Idempotency-Key，租户内唯一；NULL = 调用方未提供）',
  `business_type` varchar(32) NOT NULL COMMENT '业态 KTV/HOTEL/SPA/RETAIL',
  `customer_id` bigint unsigned NULL COMMENT '客户',
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/WAITING_PAYMENT/WAITING_ARRIVAL/SERVING/WAITING_SETTLEMENT/COMPLETED/CANCELLED/VOIDED/PARTIAL_REFUNDED/REFUNDED',
  `currency_code` char(3) NOT NULL DEFAULT 'USD' COMMENT '币种（结账快照，缺省 USD）',
  `subtotal_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '小计金额（最小货币单位）',
  `discount_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '优惠金额（最小货币单位）',
  `tax_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '税额（最小货币单位）',
  `total_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '应付总额（最小货币单位）',
  `paid_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '已收金额（最小货币单位）',
  `refundable_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '可退金额（最小货币单位）',
  `completed_at` datetime(3) NULL COMMENT '完成时间',
  `cancelled_at` datetime(3) NULL COMMENT '取消时间',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `hold_reason` varchar(255) NULL COMMENT '挂单原因',
  `hold_at` datetime(3) NULL COMMENT '挂单时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_order_tenant_no` (`tenant_id`, `order_no`),
  UNIQUE KEY `uk_ord_order_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_ord_order_store_status` (`tenant_id`, `store_id`, `status`, `created_at`),
  KEY `idx_ord_order_customer` (`tenant_id`, `customer_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一订单';

CREATE TABLE `ord_order_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '订单明细ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '订单发生门店快照',
  `business_type` varchar(32) NULL COMMENT '订单业态快照',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID（ord_order.id）',
  `item_type` varchar(32) NOT NULL COMMENT 'PRODUCT/SERVICE/PACKAGE/ROOM_FEE/ADD_ON/TAX/FEE',
  `catalog_item_id` bigint unsigned NULL COMMENT '点单目录项ID（ord_catalog_item.id）',
  `resource_id` bigint unsigned NULL COMMENT '资源ID（res_resource.id，包厢/服务人员等）',
  `name_snapshot` varchar(255) NOT NULL COMMENT '名称快照',
  `unit_price` decimal(20,6) NOT NULL COMMENT '单价（最小货币单位）',
  `quantity` decimal(20,6) NOT NULL COMMENT '数量',
  `discount_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '优惠金额（最小货币单位）',
  `tax_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '税额（最小货币单位）',
  `total_amount` decimal(20,6) NOT NULL COMMENT '小计金额（最小货币单位）',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（明细落库时的租户币种，已结算明细锁定，改设置不改历史）',
  `price_snapshot_json` json NULL COMMENT '价格快照(JSON)',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '明细状态 ACTIVE/CANCELLED 等',
  `source` varchar(24) NOT NULL DEFAULT 'MERCHANT' COMMENT '加项来源 MERCHANT/CUSTOMER',
  `inventory_status` varchar(24) NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT '库存处理状态',
  `inventory_material_id` bigint unsigned NULL COMMENT '扣减物料ID',
  `inventory_recovery_decision` varchar(24) NULL COMMENT '库存回补决定：RECOVERED/NOT_RECOVERED',
  `inventory_recovery_reason` varchar(500) NULL COMMENT '库存回补决定原因',
  `inventory_recovery_decided_by` bigint unsigned NULL COMMENT '库存回补决定人',
  `inventory_recovery_decided_at` datetime(3) NULL COMMENT '库存回补决定时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ord_order_item_order` (`tenant_id`, `order_id`),
  KEY `idx_ord_order_item_tenant_store_type` (`tenant_id`, `store_id`, `business_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细';

CREATE TABLE `ord_ktv_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '包厢会话ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '订单发生门店快照',
  `business_type` varchar(32) NULL COMMENT '订单业态快照',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID（ord_order.id）',
  `room_resource_id` bigint unsigned NOT NULL COMMENT '包厢资源',
  `occupation_id` bigint unsigned NULL COMMENT '资源占用ID（开台占用，结台释放）',
  `room_name_snapshot` varchar(128) NULL COMMENT '包厢名称快照',
  `room_code_snapshot` varchar(64) NULL COMMENT '包厢编码快照',
  `party_size` int NULL COMMENT '到店人数（开台登记，NULL=未登记）',
  `server_id` bigint unsigned NULL COMMENT '服务人员资源ID快照',
  `server_name` varchar(64) NULL COMMENT '服务人员名称快照（资源服务不可达时为 NULL）',
  `reserved_start_at` datetime(3) NULL COMMENT '预约开始时间',
  `reserved_end_at` datetime(3) NULL COMMENT '预约结束时间',
  `opened_at` datetime(3) NULL COMMENT '开台时间',
  `closed_at` datetime(3) NULL COMMENT '结台时间',
  `billing_unit` varchar(16) NOT NULL DEFAULT 'HOUR' COMMENT 'HOUR/HALF_HOUR/PACKAGE',
  `billing_start_at` datetime(3) NULL COMMENT '起算时间',
  `free_wait_minutes` int NOT NULL DEFAULT 0 COMMENT '免等待分钟数',
  `paused_seconds` int NOT NULL DEFAULT 0 COMMENT '累计暂停秒数',
  `overtime_rate` decimal(20,6) NOT NULL DEFAULT 1.0 COMMENT '超时费率',
  `billing_rule_snapshot_json` json NULL COMMENT '计价规则快照(JSON)',
  `status` varchar(24) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/OPEN/PAUSED/CLOSED/CANCELLED',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `pause_started_at` datetime(3) NULL COMMENT '当前暂停开始时间',
  PRIMARY KEY (`id`),
  KEY `idx_ord_ktv_session_room` (`tenant_id`, `room_resource_id`, `status`),
  KEY `idx_ord_ktv_session_store_type` (`tenant_id`, `store_id`, `business_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV包厢会话';

CREATE TABLE `ord_ktv_server_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '服务人员点单会话ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '订单发生门店快照',
  `business_type` varchar(32) NULL COMMENT '订单业态快照',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID（ord_order.id）',
  `ktv_session_id` bigint unsigned NULL COMMENT '关联包厢会话',
  `server_resource_id` bigint unsigned NOT NULL COMMENT '服务人员资源',
  `catalog_item_id` bigint unsigned NULL COMMENT '服务目录item',
  `ordered_at` datetime(3) NULL COMMENT '点单时间',
  `started_at` datetime(3) NULL COMMENT '开始服务时间',
  `ended_at` datetime(3) NULL COMMENT '结束服务时间',
  `billing_unit` varchar(16) NOT NULL DEFAULT 'HOUR' COMMENT 'HOUR/HALF_HOUR/PACKAGE',
  `increment_minutes` int NOT NULL DEFAULT 30 COMMENT '递增粒度分钟数',
  `rounding_direction` varchar(24) NOT NULL DEFAULT 'CONSUMER_FAVOR' COMMENT '取整方向 CONSUMER_FAVOR/MERCHANT_FAVOR',
  `price_per_inc` bigint NOT NULL COMMENT '每递增粒度单价（最小货币单位）',
  `duration_seconds` int NOT NULL DEFAULT 0 COMMENT '服务时长（秒）',
  `duration_minutes` int NOT NULL DEFAULT 0 COMMENT '计费时长（分钟）',
  `total_amount` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '服务人员费合计（最小货币单位）',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（服务人员费 total_amount 落库时的租户币种）',
  `price_snapshot_json` json NULL COMMENT '价格快照(JSON)',
  `status` varchar(16) NOT NULL DEFAULT 'ORDERED' COMMENT 'ORDERED/SERVING/ENDED/CANCELLED',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ord_ktv_server_session_order` (`tenant_id`, `order_id`),
  KEY `idx_ord_ktv_server_session_server` (`tenant_id`, `server_resource_id`, `status`),
  KEY `idx_ord_ktv_server_session_store_type` (`tenant_id`, `store_id`, `business_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV服务人员点单';

CREATE TABLE `ord_reservation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '预约ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（租户隔离）',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `reservation_no` varchar(64) NOT NULL COMMENT '预约单号',
  `idempotency_key` varchar(64) NULL COMMENT '幂等键（请求头 Idempotency-Key，租户内唯一）',
  `customer_id` bigint unsigned NULL COMMENT '租户客户ID（cst_customer，关联 SaaS 账号 account_id）',
  `business_type` varchar(32) NOT NULL COMMENT '业态 KTV/HOTEL/SPA/MASSAGE/RETAIL',
  `resource_id` bigint unsigned NULL COMMENT '实际使用包厢ID（res_resource.id）；预约创建时不写，到店分配包厢（assign-room）时写入；历史行=创建预约时直接选定的包厢',
  `room_type_id` bigint unsigned NULL COMMENT '预约房型ID（res_room_type.id，同门店）；创建预约（按房型）时写入；NULL=历史预约（仅 resource_id 驱动）',
  `start_at` datetime(3) NOT NULL COMMENT '门店营业本地时间（+08:00）',
  `end_at` datetime(3) NOT NULL COMMENT '门店营业本地时间（+08:00）',
  `party_size` int unsigned NOT NULL DEFAULT 1 COMMENT '预约人数',
  `contact` varchar(128) NULL COMMENT '预订联系人（姓名 手机号）',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/ARRIVED/CANCELLED/NO_SHOW/CONVERTED',
  `order_id` bigint unsigned NULL COMMENT '到店后转统一订单ID（ord_order.id）',
  `arrived_at` datetime(3) NULL COMMENT '实际到店时间（门店登记到店或到店开台时写入；未到店/历史行为 NULL）',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID，0 表示系统',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID，0 表示系统',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_reservation_tenant_no` (`tenant_id`, `reservation_no`),
  UNIQUE KEY `uk_ord_reservation_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_ord_reservation_store_status` (`tenant_id`, `store_id`, `status`, `start_at`),
  KEY `idx_ord_reservation_customer` (`tenant_id`, `customer_id`, `start_at`),
  KEY `idx_ord_reservation_resource` (`tenant_id`, `resource_id`, `start_at`, `status`),
  KEY `idx_ord_reservation_room_type` (`tenant_id`, `store_id`, `room_type_id`, `start_at`, `status`),
  CONSTRAINT `ck_ord_reservation_party_size` CHECK (`party_size` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约（SaaS 多租户，E-MIG）';

CREATE TABLE `ord_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Outbox记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 order.created/order.settled)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 order)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `payload_json` json NOT NULL COMMENT '事件载荷(最小化，含租户上下文)',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime(3) NULL COMMENT '下次重试时间(指数退避)',
  `published_at` datetime(3) NULL COMMENT '投递成功时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统；本模块只读该表，写入由订单业务事务完成）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统；Relay 只更新状态列）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_event_outbox_event` (`event_id`),
  KEY `idx_ord_event_outbox_pending` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单事件 Outbox';

CREATE TABLE `ord_event_consumed` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '去重记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(消费幂等键)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 order.created)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 order)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `consumed_at` datetime(3) NOT NULL COMMENT '消费时间',
  -- 审计列：写入方（OrderCreatedEventConsumer / OrderSettledEventConsumer 经 BaseMapper.insert 写入）
  -- 只写 consumed_at，不写这四个审计列，故 created_at/updated_at 用 DEFAULT CURRENT_TIMESTAMP(3) 由库填充
  -- （追加型去重表，updated_at 仅在插入时刻取值，后续不更新）。
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（库默认值填充）',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（库默认值填充）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_event_consumed_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单事件消费去重表';

CREATE TABLE `ord_catalog_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '目录项ID',
  `product_id` bigint unsigned NULL COMMENT '库存商品ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '门店ID，NULL=租户级通用',
  `category` varchar(64) NOT NULL COMMENT '分类 酒水/小吃/加钟/服务/套餐/其他',
  `item_type` varchar(32) NOT NULL DEFAULT 'PRODUCT' COMMENT 'PRODUCT=商品 SERVICE=服务 PACKAGE=套餐 ADD_ON=加项',
  `name` varchar(255) NOT NULL COMMENT '名称',
  `unit` varchar(32) NOT NULL DEFAULT '份' COMMENT '单位 瓶/份/小时/次/套',
  `unit_price` decimal(20,6) NOT NULL COMMENT '单价（最小货币单位：分）',
  `description` varchar(500) NULL COMMENT '描述',
  `image_urls` json NULL COMMENT '目录项图片URL列表(JSON数组，最多9张，镜像自关联商品/物料)',
  `main_image_url` varchar(512) NULL COMMENT '目录项主图URL(镜像自关联商品/物料)',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `stock_controlled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否受库存控制',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ord_catalog_item_tenant_store_cat` (`tenant_id`, `store_id`, `category`, `status`),
  KEY `idx_ord_catalog_item_tenant_store_status` (`tenant_id`, `store_id`, `status`),
  KEY `idx_ord_catalog_item_product_status` (`tenant_id`, `store_id`, `product_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品/服务目录';

CREATE TABLE `ord_inventory_material` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '物料ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `material_code` varchar(64) NOT NULL COMMENT '门店内物料编码',
  `name` varchar(255) NOT NULL COMMENT '物料名称',
  `category` varchar(64) NOT NULL COMMENT '物料分类',
  `unit` varchar(32) NOT NULL COMMENT '计量单位',
  `description` varchar(255) NULL COMMENT '描述',
  `safety_stock` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '安全库存',
  `purchase_price` decimal(20,6) NULL COMMENT '采购价（最小货币单位：分，每计量单位）',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '采购价币种（purchase_price 的计价币种快照）',
  `image_urls` json NULL COMMENT '物料图片URL列表(JSON数组，最多9张)',
  `main_image_url` varchar(512) NULL COMMENT '物料主图URL',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_material_store_code` (`tenant_id`, `store_id`, `material_code`),
  KEY `idx_ord_inventory_material_store_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存物料档案';

CREATE TABLE `ord_inventory_stock` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '库存余额ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `material_id` bigint unsigned NOT NULL COMMENT '物料ID',
  `on_hand_qty` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '现有库存',
  `reserved_qty` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '预占库存',
  `avg_cost` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '移动加权平均成本（最小货币单位/计量单位）',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '移动加权平均成本币种快照',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_stock_material` (`tenant_id`, `store_id`, `material_id`),
  KEY `idx_ord_inventory_stock_store` (`tenant_id`, `store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存余额';

CREATE TABLE `ord_inventory_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '库存流水ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `material_id` bigint unsigned NOT NULL COMMENT '物料ID',
  `transaction_type` varchar(24) NOT NULL COMMENT 'RECEIPT/CONSUME/ADJUST_IN/ADJUST_OUT/REVERSE',
  `quantity_delta` decimal(20,6) NOT NULL COMMENT '库存变化量',
  `quantity_before` decimal(20,6) NOT NULL COMMENT '变化前库存',
  `quantity_after` decimal(20,6) NOT NULL COMMENT '变化后库存',
  `unit_cost` decimal(20,6) NULL COMMENT '单价（最小货币单位/计量单位；入库=批次单价，出库=移动加权平均成本）',
  `total_cost` decimal(20,6) NULL COMMENT '成本发生额（最小货币单位，带符号：入库正、出库负）',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '单价/发生额币种快照',
  `source_type` varchar(32) NOT NULL COMMENT 'RECEIPT/ORDER_ITEM/ADJUSTMENT',
  `source_id` varchar(64) NULL COMMENT '来源业务ID',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `reason` varchar(500) NULL COMMENT '原因',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人',
  `created_at` datetime(3) NOT NULL COMMENT '发生时间',
  -- 审计列：写入方（InventoryApplicationService 经 BaseMapper.insert 写入）只写 created_by/created_at，
  -- 本表是追加型流水（无 UPDATE 路径），故 updated_at 用 DEFAULT CURRENT_TIMESTAMP(3) 由库填充，
  -- updated_by 用 DEFAULT 0（系统自动产生的流水）。
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（库默认值填充，插入时刻等于发生时间）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_inventory_transaction_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_ord_inventory_transaction_material_time` (`tenant_id`, `store_id`, `material_id`, `created_at`),
  KEY `idx_ord_inventory_transaction_source` (`tenant_id`, `source_type`, `source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存变动流水';

CREATE TABLE `ord_product` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `product_code` varchar(64) NOT NULL COMMENT '门店内商品编码',
  `name` varchar(255) NOT NULL COMMENT '商品名称',
  `category` varchar(64) NOT NULL COMMENT '商品分类',
  `item_type` varchar(16) NOT NULL DEFAULT 'PRODUCT' COMMENT '商品类型：PRODUCT 实物商品 / SERVICE 服务',
  `unit` varchar(32) NOT NULL COMMENT '销售单位',
  `sale_price` decimal(20,6) NOT NULL COMMENT '销售单价（分）',
  `material_id` bigint unsigned NULL COMMENT '关联库存物料',
  `server_resource_id` bigint unsigned NULL COMMENT '服务人员ID(res_resource.id，resource_type=KTV_SERVER；仅 item_type=SERVICE)',
  `stock_controlled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否扣库存',
  `catalog_item_id` bigint unsigned NULL COMMENT '兼容目录项ID',
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ON_SHELF/OFF_SHELF',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `description` varchar(500) NULL COMMENT '描述',
  `image_urls` json NULL COMMENT '商品图片URL列表(JSON数组，最多9张)',
  `main_image_url` varchar(512) NULL COMMENT '商品主图URL',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_product_store_code` (`tenant_id`, `store_id`, `product_code`),
  UNIQUE KEY `uk_ord_product_catalog_item` (`tenant_id`, `catalog_item_id`),
  UNIQUE KEY `uk_ord_product_server_resource` (`tenant_id`, `store_id`, `server_resource_id`),
  KEY `idx_ord_product_store_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV可售商品';

CREATE TABLE `ord_product_category` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `name` varchar(64) NOT NULL COMMENT '分类名称',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_product_category_store_name` (`tenant_id`, `store_id`, `name`),
  KEY `idx_ord_product_category_store_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KTV商品分类';

CREATE TABLE `ord_daily_serial` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '序号记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（单号唯一域与 (tenant_id, order_no) 一致）',
  `biz_type` varchar(16) NOT NULL COMMENT '单据类型 ORDER / RESERVATION（决定单号前缀 O / R）',
  `business_date` date NOT NULL COMMENT '营业日（Asia/Shanghai + 04:00 切点，见 StoreTimeService）',
  `current_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '该营业日已分配到的序号（0 = 尚未分配）',
  -- 审计列：写入方 DailySerialMapper.insertFirst 是手写 INSERT，只写 created_at/updated_at，
  -- 故 created_by/updated_by 用 DEFAULT 0（发号由系统执行，无操作者）。
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_daily_serial_key` (`tenant_id`, `biz_type`, `business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单据号每日序号（订单/预约，仅服务新建单据）';

CREATE TABLE `ord_reservation_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '空字符串=租户默认',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '0=租户/业态默认，大于0=门店覆盖',
  `advance_minutes` int unsigned NOT NULL DEFAULT 0 COMMENT '允许提前预约分钟数；0=不限制',
  `cancel_minutes` int unsigned NOT NULL DEFAULT 0 COMMENT '到店前最少取消分钟数；0=不限制',
  `reschedule_minutes` int unsigned NOT NULL DEFAULT 0 COMMENT '到店前最少改期分钟数；0=不限制',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `idempotency_key` varchar(128) NULL COMMENT '幂等键（租户内唯一，NULL=调用方未提供）',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_reservation_rule_config_scope` (`tenant_id`, `business_type`, `store_id`),
  UNIQUE KEY `uk_ord_reservation_rule_config_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_ord_reservation_rule_config_resolve` (`tenant_id`, `business_type`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约经营规则';

CREATE TABLE `ord_order_void_approval` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '作废审批单ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `business_type` varchar(64) NOT NULL DEFAULT '' COMMENT '业态（空字符串=租户/全业态默认）',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID（ord_order.id）',
  `order_version` int NOT NULL DEFAULT 0 COMMENT '提交时订单版本号',
  `order_status_snapshot` varchar(32) NOT NULL COMMENT '提交时订单状态快照',
  `order_snapshot_json` text NULL COMMENT '提交时订单快照(JSON)',
  `reason` varchar(512) NOT NULL COMMENT '作废原因',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/EXECUTED',
  `applicant_id` bigint unsigned NOT NULL COMMENT '申请人ID',
  `approver_id` bigint unsigned NULL COMMENT '审批人ID（未审批为 NULL）',
  `review_comment` varchar(512) NULL COMMENT '审批意见',
  `idempotency_key` varchar(128) NULL COMMENT '幂等键（租户内唯一，NULL=调用方未提供）',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  `reviewed_at` datetime(3) NULL COMMENT '审批时间（未审批为 NULL）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_order_void_approval_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_ord_order_void_approval_order` (`tenant_id`, `order_id`, `status`),
  KEY `idx_ord_order_void_approval_store` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单作废审批';

CREATE TABLE `ord_void_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `business_type` varchar(64) NOT NULL DEFAULT '' COMMENT '业态（空字符串=租户/全业态默认）',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '0=租户/业态默认，>0=门店覆盖',
  `require_approval` tinyint(1) NOT NULL DEFAULT 0 COMMENT '作废是否需要审批 0=否 1=是',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `idempotency_key` varchar(128) NULL COMMENT '幂等键（租户内唯一，NULL=调用方未提供）',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人ID（0=系统）',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人ID（0=系统）',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_void_rule_config_scope` (`tenant_id`, `business_type`, `store_id`),
  UNIQUE KEY `uk_ord_void_rule_config_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_ord_void_rule_config_resolve` (`tenant_id`, `business_type`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单作废审批规则';

-- ============================================================================
-- 二、种子数据（原 V9 的 A380 KTV 点单目录；单价单位：分）
-- ============================================================================

-- A380 KTV（tenant_id=100, store_id=100）种子目录。
-- 幂等：以主键 id 命中时只刷新业务字段，updated_at 保持原值（与原语句的 ON DUPLICATE KEY UPDATE 一致）。
-- product_id / stock_controlled / image_urls / main_image_url 不写入：原 V9 建表时这些列尚不存在，
-- 累计最终态里它们取列默认值（NULL / 0 / NULL / NULL），与本 INSERT 的省略写法一致。
INSERT INTO `ord_catalog_item` (`id`, `tenant_id`, `store_id`, `category`, `item_type`, `name`, `unit`, `unit_price`, `description`, `status`, `sort_order`, `created_at`, `updated_at`) VALUES
(1, 100, 100, '酒水', 'PRODUCT', '百威啤酒', '瓶', 1500, '经典拉格，330ml', 'ACTIVE', 1, NOW(3), NOW(3)),
(2, 100, 100, '酒水', 'PRODUCT', '科罗娜', '瓶', 2500, '配青柠更佳，330ml', 'ACTIVE', 2, NOW(3), NOW(3)),
(3, 100, 100, '酒水', 'PRODUCT', '矿泉水', '瓶', 500, '农夫山泉 550ml', 'ACTIVE', 3, NOW(3), NOW(3)),
(4, 100, 100, '酒水', 'PRODUCT', '可乐', '瓶', 800, '罐装 330ml', 'ACTIVE', 4, NOW(3), NOW(3)),
(5, 100, 100, '小吃', 'PRODUCT', '时令果盘', '份', 4800, '当季水果拼盘', 'ACTIVE', 1, NOW(3), NOW(3)),
(6, 100, 100, '小吃', 'PRODUCT', '爆米花', '份', 2800, '焦糖口味', 'ACTIVE', 2, NOW(3), NOW(3)),
(7, 100, 100, '小吃', 'PRODUCT', '鸭脖', '份', 3800, '香辣鸭脖', 'ACTIVE', 3, NOW(3), NOW(3)),
(8, 100, 100, '加钟', 'ADD_ON', '加钟 1 小时', '小时', 10000, '包厢延时 1 小时', 'ACTIVE', 1, NOW(3), NOW(3)),
(9, 100, 100, '服务', 'SERVICE', '服务员点歌', '次', 1000, '专属服务员协助点歌', 'ACTIVE', 1, NOW(3), NOW(3)),
(10, 100, 100, '套餐', 'PACKAGE', '欢唱套餐', '套', 28800, '含 2 小时包厢 + 果盘 + 啤酒 6 瓶', 'ACTIVE', 1, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `category` = VALUES(`category`), `item_type` = VALUES(`item_type`), `name` = VALUES(`name`),
  `unit` = VALUES(`unit`), `unit_price` = VALUES(`unit_price`), `description` = VALUES(`description`), `updated_at` = `updated_at`;

-- ============================================================================
-- 三、数据订正（保留原迁移的谓词与执行顺序）
-- 全新库上这些语句的源表（ord_product / ord_inventory_material / ord_inventory_stock /
-- ord_inventory_transaction）与目标行都不存在或已是列默认值，因此均为幂等空操作；
-- 保留它们是为了不丢原迁移表达的订正口径（升级既有库时同样成立）。
-- ============================================================================

-- 原 V14 迁移：目录项图片回填（先按关联商品，再按「商品 → 物料」链路）。
UPDATE `ord_catalog_item` AS `c`
  JOIN `ord_product` AS `p`
    ON `p`.`tenant_id` = `c`.`tenant_id` AND `p`.`id` = `c`.`product_id`
   SET `c`.`image_urls` = CAST(`p`.`image_urls` AS CHAR),
       `c`.`main_image_url` = `p`.`main_image_url`
 WHERE `c`.`product_id` IS NOT NULL
   AND `p`.`image_urls` IS NOT NULL;

UPDATE `ord_catalog_item` AS `c`
  JOIN `ord_product` AS `p`
    ON `p`.`tenant_id` = `c`.`tenant_id` AND `p`.`id` = `c`.`product_id`
  JOIN `ord_inventory_material` AS `m`
    ON `m`.`tenant_id` = `p`.`tenant_id` AND `m`.`store_id` = `p`.`store_id` AND `m`.`id` = `p`.`material_id`
   SET `c`.`image_urls` = CAST(`m`.`image_urls` AS CHAR),
       `c`.`main_image_url` = `m`.`main_image_url`
 WHERE `c`.`product_id` IS NOT NULL
   AND `c`.`image_urls` IS NULL
   AND `m`.`image_urls` IS NOT NULL;

-- 原 V17 迁移：目录项 stock_controlled / product_id 回填。
-- 1) 商品侧回指（ord_product.catalog_item_id）为权威关联。
UPDATE `ord_catalog_item` AS `c`
  JOIN `ord_product` AS `p`
    ON `p`.`catalog_item_id` = `c`.`id`
   AND `p`.`tenant_id` = `c`.`tenant_id`
   SET `c`.`stock_controlled` = `p`.`stock_controlled`,
       `c`.`product_id` = `p`.`id`;

-- 2) 兼容只有目录项侧回指（ord_catalog_item.product_id）的历史数据。
UPDATE `ord_catalog_item` AS `c`
  JOIN `ord_product` AS `p`
    ON `p`.`id` = `c`.`product_id`
   AND `p`.`tenant_id` = `c`.`tenant_id`
   SET `c`.`stock_controlled` = `p`.`stock_controlled`;

-- 原 V21 迁移：公开媒体 URL 前缀 /media-public/ 改写为 /api/v1/media-public/。
-- 只命中「值以 /media-public/ 开头」的前缀；改写后的值以 /api/v1/ 开头，重复执行匹配 0 行（幂等）。
UPDATE `ord_product`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `ord_product`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';

UPDATE `ord_inventory_material`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `ord_inventory_material`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';

UPDATE `ord_catalog_item`
   SET `image_urls` = REPLACE(CAST(`image_urls` AS CHAR), '"/media-public/', '"/api/v1/media-public/')
 WHERE `image_urls` IS NOT NULL
   AND CAST(`image_urls` AS CHAR) LIKE '%"/media-public/%';

UPDATE `ord_catalog_item`
   SET `main_image_url` = CONCAT('/api/v1/media-public/', SUBSTRING(`main_image_url`, CHAR_LENGTH('/media-public/') + 1))
 WHERE `main_image_url` LIKE '/media-public/%';

-- 原 V22 迁移：币种快照历史行统一回填 USD（不猜测、不做汇率换算；顺带兜住 '' 脏值）。
-- 三列均为 NOT NULL DEFAULT 'USD'，新库/新行不会出现 NULL 或 ''。
UPDATE `ord_order_item` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `ord_ktv_server_session` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `ord_inventory_material` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `ord_order` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';

-- 原 V24 迁移：流水币种回填 + 库存结存按物料采购价建账（一次性近似，不追溯重估）。
UPDATE `ord_inventory_transaction` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';

UPDATE `ord_inventory_stock` AS `s`
JOIN `ord_inventory_material` AS `m`
  ON `m`.`tenant_id` = `s`.`tenant_id` AND `m`.`store_id` = `s`.`store_id` AND `m`.`id` = `s`.`material_id`
SET `s`.`avg_cost` = `m`.`purchase_price`,
    `s`.`currency_code` = COALESCE(NULLIF(`m`.`currency_code`, ''), 'USD')
WHERE `s`.`on_hand_qty` > 0
  AND `m`.`purchase_price` IS NOT NULL
  AND `m`.`purchase_price` > 0;

-- 原 V26 迁移：历史商品一律归为实物商品 PRODUCT（列默认值已保证，这里做双保险）。
UPDATE `ord_product` SET `item_type` = 'PRODUCT' WHERE `item_type` IS NULL OR `item_type` = '';
