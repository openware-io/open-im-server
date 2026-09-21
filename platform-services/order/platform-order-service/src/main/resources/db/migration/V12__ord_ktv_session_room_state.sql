-- 包厢运行状态关联：开台占用资源（occupation_id）、包厢名称/编码快照（订单列表与账单展示包厢）。
ALTER TABLE `ord_ktv_session`
  ADD COLUMN `occupation_id` bigint unsigned NULL COMMENT '资源占用ID（开台占用，结台释放）' AFTER `room_resource_id`,
  ADD COLUMN `room_name_snapshot` varchar(128) NULL COMMENT '包厢名称快照' AFTER `occupation_id`,
  ADD COLUMN `room_code_snapshot` varchar(64) NULL COMMENT '包厢编码快照' AFTER `room_name_snapshot`;
