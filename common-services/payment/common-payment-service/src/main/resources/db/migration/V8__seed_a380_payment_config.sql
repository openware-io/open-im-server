-- A380 线上支付渠道（默认关闭，现金/A380币/积分不依赖渠道配置）
INSERT INTO pay_channel_config (tenant_id, store_id, channel, enabled, status, created_at, updated_at) VALUES
(100, 100, 'ALIPAY', 0, 'ACTIVE', NOW(3), NOW(3)),
(100, 100, 'WECHAT', 0, 'ACTIVE', NOW(3), NOW(3)),
(100, 100, 'STRIPE', 0, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE updated_at = updated_at;
