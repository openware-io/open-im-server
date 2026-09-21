-- A380 租户初始化（测试种子，后续测试可人工调整）
-- 固定 ID 便于跨服务引用：tenant/org/store/legal_entity/pricing_plan = 100，门店资源 = 1001+
INSERT INTO tnt_tenant (id, tenant_code, name, status, default_locale, default_timezone, created_at, updated_at)
VALUES (100, 'a380', 'A380', 'ACTIVE', 'zh-CN', 'Asia/Shanghai', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = tnt_tenant.updated_at;

INSERT INTO tnt_organization (id, tenant_id, code, name, status, created_at, updated_at)
VALUES (100, 100, 'a380-hq', 'A380 总部', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = tnt_organization.updated_at;

INSERT INTO tnt_store (id, tenant_id, organization_id, code, name, business_type, country_code, timezone, default_currency, locale, status, created_at, updated_at)
VALUES (100, 100, 100, 'a380-ktv-001', 'A380 KTV 旗舰店', 'KTV', 'CN', 'Asia/Shanghai', 'CNY', 'zh-CN', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = tnt_store.updated_at;

INSERT INTO tnt_legal_entity (id, tenant_id, name, unified_social_credit_code, currency_code, status, created_at, updated_at)
VALUES (100, 100, '深圳市A380娱乐有限公司', '91440300MA5A380001', 'CNY', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = tnt_legal_entity.updated_at;

-- 租户配置：储值品牌名 + 默认法人主体
INSERT INTO tnt_tenant_config (tenant_id, config_key, config_value, status, created_at, updated_at) VALUES
(100, 'wallet_brand_name', 'A380币', 'ACTIVE', NOW(3), NOW(3)),
(100, 'default_legal_entity_id', '100', 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = tnt_tenant_config.updated_at;

-- KTV 计价方案（按门店/资源类型唯一）：¥128/小时，标准 120 分钟，超时 1.0x
INSERT INTO tnt_pricing_plan (id, tenant_id, store_id, resource_type, billing_unit, increment_minutes, rounding_direction, price_per_unit, default_session_minutes, overtime_rate, status, created_at, updated_at)
VALUES (100, 100, 100, 'KTV_ROOM', 'HOUR', 30, 'CONSUMER_FAVOR', 12800, 120, 1.0, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = tnt_pricing_plan.updated_at;
