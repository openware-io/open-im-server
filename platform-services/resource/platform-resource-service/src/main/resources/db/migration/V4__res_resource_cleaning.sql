-- 包厢清洁状态：清洁中的资源不可用（既不能开台也不能被预约）。
-- 业务口径：结台后包厢自动进入 CLEANING，服务员清洁完成后由门店端标记回 IDLE。
ALTER TABLE `res_resource`
  ADD COLUMN `cleaning_status` varchar(24) NOT NULL DEFAULT 'IDLE' COMMENT '清洁状态 IDLE/CLEANING' AFTER `status`,
  ADD COLUMN `cleaning_started_at` datetime(3) NULL COMMENT '进入清洁中时间' AFTER `cleaning_status`;
