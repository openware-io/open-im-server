-- C 端预约迁移（E-MIG）· 为 ord_reservation 增加幂等键（SAAS_PLATFORM_05 §5.3 写契约的 Idempotency-Key）
-- 目标库：gv_saas（platform-order-service）。仅新增列 + 唯一索引，不影响已回填数据（回填行 idempotency_key 为 NULL）。
-- NULL 在 MySQL 唯一索引中允许多行，因此历史回填/未携带幂等键的创建不冲突。
ALTER TABLE `ord_reservation`
  ADD COLUMN `idempotency_key` varchar(64) NULL COMMENT '幂等键（请求头 Idempotency-Key，租户内唯一）' AFTER `reservation_no`,
  ADD UNIQUE KEY `uk_ord_reservation_idem` (`tenant_id`, `idempotency_key`);
