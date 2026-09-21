-- 预约时间列注释纠正：ord_reservation.start_at / end_at 存的是**门店营业本地时间**，不是 UTC。
--
-- 历史 V2__ord_reservation.sql 的列注释写成「（UTC）」，但写路径
-- （ReservationApplicationService#toBusinessLocal）与读路径（前端直接渲染原始值）都按 +08:00 本地墙上时间处理：
-- 入参是带偏移的 OffsetDateTime，落库前统一换算到 +08:00 再去掉偏移。注释与实现不一致会让接入方
-- 误以为要自己再减 8 小时，从而出现 8 小时偏差。
--
-- 已执行的 V2 不能改（Flyway 校验和不可变），因此用新迁移 MODIFY COLUMN 只更新注释，不改类型与可空性。
-- 注意：MODIFY COLUMN 会以本语句给出的完整定义重写列（这里保持 datetime(3) NOT NULL 原样）。
ALTER TABLE `ord_reservation`
  MODIFY COLUMN `start_at` datetime(3) NOT NULL COMMENT '门店营业本地时间（+08:00）',
  MODIFY COLUMN `end_at` datetime(3) NOT NULL COMMENT '门店营业本地时间（+08:00）';
