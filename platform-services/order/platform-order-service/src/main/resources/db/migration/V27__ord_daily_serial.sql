-- 单据号「每日序号」分配表（订单号 O<yyyyMMdd><seq> / 预约号 R<yyyyMMdd><seq>）。
--
-- 背景：此前订单号是 "O" + System.currentTimeMillis()（纯时间戳，看不出日期、无当日序号、同毫秒并发生重），
-- 预约号是 "R" + UUID 前 20 位（完全无信息量）。新规则要求「人眼看号就知道日期与当日第几单」。
--
-- 口径（实现见 com.gvchat.platform.order.application.DailySerialNumberGenerator）：
--   * business_date 是**营业日**（平台默认 Asia/Shanghai + 04:00 切点，复用 StoreTimeService），
--     不是自然日、也不是 UTC 日；
--   * 序号按 **租户**（不含门店）维度每日从 1 递增 —— 因为 ord_order 的唯一键是
--     uk_ord_order_tenant_no (tenant_id, order_no)：若按门店各自从 1 开始，同租户两个门店同日
--     会生成同一个单号，直接撞唯一键；本表唯一键与之一致；
--   * 定宽最小 4 位（0001）；同一天超过 9999 单**自然加宽**（10000 → 5 位），不回绕、不截断。
--
-- 历史数据：本迁移**只建表**，不 UPDATE 任何 ord_order / ord_reservation 行；历史单号原样保留。
CREATE TABLE `ord_daily_serial` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（单号唯一域与 (tenant_id, order_no) 一致）',
  `biz_type` varchar(16) NOT NULL COMMENT '单据类型 ORDER / RESERVATION（决定单号前缀 O / R）',
  `business_date` date NOT NULL COMMENT '营业日（Asia/Shanghai + 04:00 切点，见 StoreTimeService）',
  `current_seq` bigint unsigned NOT NULL DEFAULT 0 COMMENT '该营业日已分配到的序号（0 = 尚未分配）',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ord_daily_serial_key` (`tenant_id`, `biz_type`, `business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单据号每日序号（订单/预约，仅服务新建单据）';
