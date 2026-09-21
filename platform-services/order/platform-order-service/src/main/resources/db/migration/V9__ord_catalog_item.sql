-- 商品/服务目录：B 端点单与 C 端自助加项从目录选择（参考业界 KTV 点单：分类 + 商品/服务 + 单价 + 单位）。
CREATE TABLE `ord_catalog_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '目录项ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '门店ID，NULL=租户级通用',
  `category` varchar(64) NOT NULL COMMENT '分类 酒水/小吃/加钟/服务/套餐/其他',
  `item_type` varchar(32) NOT NULL DEFAULT 'PRODUCT' COMMENT 'PRODUCT=商品 SERVICE=服务 PACKAGE=套餐 ADD_ON=加项',
  `name` varchar(255) NOT NULL COMMENT '名称',
  `unit` varchar(32) NOT NULL DEFAULT '份' COMMENT '单位 瓶/份/小时/次/套',
  `unit_price` decimal(20,6) NOT NULL COMMENT '单价（最小货币单位：分）',
  `description` varchar(500) NULL COMMENT '描述',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '排序',
  `created_by` bigint unsigned NOT NULL DEFAULT 0, `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0, `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ord_catalog_tenant_store_cat` (`tenant_id`, `store_id`, `category`, `status`),
  KEY `idx_ord_catalog_tenant_store_status` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品/服务目录';

-- A380 KTV（tenant_id=100, store_id=100）种子目录（单价单位：分）
INSERT INTO ord_catalog_item (id, tenant_id, store_id, category, item_type, name, unit, unit_price, description, status, sort_order, created_at, updated_at) VALUES
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
ON DUPLICATE KEY UPDATE category = VALUES(category), item_type = VALUES(item_type), name = VALUES(name),
  unit = VALUES(unit), unit_price = VALUES(unit_price), description = VALUES(description), updated_at = updated_at;
