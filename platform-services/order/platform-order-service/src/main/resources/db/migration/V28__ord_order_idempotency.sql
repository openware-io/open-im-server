-- 快速开台（POST /business/orders）幂等键：客户端重试（网络抖动、用户重复点击、App 自动重发）不应产生第二张订单。
--
-- 背景：`ord_order` 此前没有幂等键，而 B 端 App 的写请求拦截器**每次都会带 Idempotency-Key**，
-- 服务端却完全忽略它 —— 一次重试就多一张 DRAFT 单（房态看板上表现为同一包厢两张「使用中」订单）。
-- 预约（V3）与库存流水（V11）早已有同款唯一键，本次把订单补齐到同一口径：
--   * 列可空：调用方不带 Idempotency-Key 时行为完全不变（历史/内部调用不受影响）；
--   * 唯一键 (tenant_id, idempotency_key)：并发重复提交由数据库兜底，服务端命中后回放已有订单。
--
-- 目标库：open_saas（platform-order-service），Flyway 历史表 flyway_schema_history_order。
ALTER TABLE `ord_order`
  ADD COLUMN `idempotency_key` varchar(64) NULL
    COMMENT '幂等键（请求头 Idempotency-Key，租户内唯一；NULL = 调用方未提供）' AFTER `order_no`,
  ADD UNIQUE KEY `uk_ord_order_idem` (`tenant_id`, `idempotency_key`);
