-- 加服务项来源：MERCHANT=商户/服务员代客加项（直接生效），CUSTOMER=消费者自助加项（待服务人员确认）。
ALTER TABLE `ord_order_item`
  ADD COLUMN `source` varchar(24) NOT NULL DEFAULT 'MERCHANT'
  COMMENT '加项来源 MERCHANT/CUSTOMER' AFTER `status`;
