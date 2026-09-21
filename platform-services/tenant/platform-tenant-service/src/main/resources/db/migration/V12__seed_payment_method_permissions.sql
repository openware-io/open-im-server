-- 支付方式资源点（payment.method.*）：SaaS 平台管控的支付方式目录（清单）
-- status=ACTIVE 表示平台可用；租户级授权在 tenant_payment_method.granted（平台显式授权，现金兜底无需授权）
INSERT INTO iam_permission (code, module, resource, action, description, status, created_at, updated_at) VALUES
('payment.method.cash','payment','method','cash','现金支付','ACTIVE',NOW(3),NOW(3)),
('payment.method.wallet','payment','method','wallet','储值币(A380币)支付','ACTIVE',NOW(3),NOW(3)),
('payment.method.point','payment','method','point','积分抵扣','ACTIVE',NOW(3),NOW(3)),
('payment.method.alipay','payment','method','alipay','支付宝','ACTIVE',NOW(3),NOW(3)),
('payment.method.wechat','payment','method','wechat','微信支付','ACTIVE',NOW(3),NOW(3)),
('payment.method.stripe','payment','method','stripe','Stripe','ACTIVE',NOW(3),NOW(3))
ON DUPLICATE KEY UPDATE module = VALUES(module), resource = VALUES(resource), action = VALUES(action), updated_at = updated_at;
