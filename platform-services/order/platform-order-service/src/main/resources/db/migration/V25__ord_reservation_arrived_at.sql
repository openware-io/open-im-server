-- 预约「真实到店时间」：把「预约开始时间（客户约的时段）」与「实际到店时间」彻底分开。
--
-- 背景（缺陷修复，见 docs/renovation/KTV_RESERVATION_ORDER_STATE_FLOW.md §4.1）：
-- 此前 ord_reservation 只有 start_at/end_at，后台预约列表把 start_at 当「到店时间」展示；
-- 而「到店分配包厢」又直接把状态置 ARRIVED（隐含客人已到店），于是「提前锁房」被误报成「客户已到店」。
-- 现在：
--   1) 分配包厢只写 resource_id，不改状态（预约锁房 ≠ 客人到店）；
--   2) 到店事实只由「到店登记」（CONFIRMED → ARRIVED）或「到店开台」（CONFIRMED → CONVERTED，隐含到店）写入；
--   3) 两种路径都写 arrived_at，后台据此展示真实到店时间（未到店为 NULL）。
--
-- 目标库：gv_saas（platform-order-service），Flyway 历史表 flyway_schema_history_order。
ALTER TABLE `ord_reservation`
  ADD COLUMN `arrived_at` datetime(3) NULL
    COMMENT '实际到店时间（门店登记到店或到店开台时写入；未到店/历史行为 NULL）' AFTER `order_id`;
