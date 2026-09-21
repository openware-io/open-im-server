-- E8 预发布：Feature Flag 灰度配置占位（tnt_tenant_config）
-- 说明：灰度按 tenant/store 维度控制。
--   1) tenant 维度：tenant_id 定位租户，store_id = 0 表示「租户级默认开关」，作用于该租户下所有门店。
--   2) store  维度：store_id > 0 表示「门店级覆盖」，仅对该门店生效（可做单店灰度放量）。
--   3) 读取优先级（占位，待灰度开关接入读取配置后落地）：
--        门店覆盖(tenant_id, store_id>0, key) > 租户默认(tenant_id, 0, key) > 全局硬编码默认
--   4) config_value 语义约定：'true'/'false'（布尔开关），后续可扩展 'percent:50'（按比例灰度）等取值。
-- 本次为 V1 已建表 tnt_tenant_config 的前向迁移：新增 store_id 维度，并将原唯一键扩展为 (tenant_id, store_id, config_key)。
ALTER TABLE `tnt_tenant_config`
  ADD COLUMN `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '门店ID，0=租户级默认（作用于所有门店），>0=门店级覆盖' AFTER `tenant_id`,
  DROP KEY `uk_tnt_tenant_config_tenant_key`,
  ADD UNIQUE KEY `uk_tnt_tenant_config_scope_key` (`tenant_id`, `store_id`, `config_key`);

-- 为默认租户（V3 种子的 default 租户）写入灰度开关默认值（幂等：依赖唯一键重复跳过）
-- 灰度起点：KTV 首发业态默认开启；线上支付（支付宝/微信/Stripe）默认关闭，与 SAAS_PLATFORM/KTV 方案一致。
INSERT INTO tnt_tenant_config (tenant_id, store_id, config_key, config_value, status, version, created_at, updated_at)
SELECT t.id, 0, 'feature.ktv.enabled', 'true', 'ACTIVE', 0, NOW(3), NOW(3)
FROM tnt_tenant t WHERE t.tenant_code = 'default'
ON DUPLICATE KEY UPDATE updated_at = tnt_tenant_config.updated_at;

INSERT INTO tnt_tenant_config (tenant_id, store_id, config_key, config_value, status, version, created_at, updated_at)
SELECT t.id, 0, 'feature.online-payment.enabled', 'false', 'ACTIVE', 0, NOW(3), NOW(3)
FROM tnt_tenant t WHERE t.tenant_code = 'default'
ON DUPLICATE KEY UPDATE updated_at = tnt_tenant_config.updated_at;
