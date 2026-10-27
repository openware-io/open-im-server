ALTER TABLE `htl_housekeeping_task`
  ADD COLUMN `task_type` varchar(24) NOT NULL DEFAULT 'DEPARTURE' COMMENT '清扫类型：STAYOVER 在住日常清扫 / DEPARTURE 退房清扫' AFTER `booking_id`,
  ADD COLUMN `source` varchar(24) NOT NULL DEFAULT 'CHECKOUT' COMMENT '来源：CUSTOMER / FRONT_DESK / CHECKOUT' AFTER `task_type`,
  ADD COLUMN `note` varchar(255) NULL COMMENT '客人清扫备注' AFTER `source`;
