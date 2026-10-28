# ADR 0009：C 端统一订单视图（跨集团/门店）

## Status / 状态

**proposed（提案中，2026-10-22）**。只确定“用户订单列表”的读模型边界，不改变各订单写模型。

## Context / 背景

C 端用户说“我的订单”时，不关心订单属于哪个集团或门店。当前订单/预约接口以租户/门店上下文为主，`OrderQuery` 也带 `tenantId`，无法直接支持：

- 同一用户在多个集团/门店下的订单；
- 酒店、KTV、其他业务的统一列表；
- 不切换租户上下文时查看全部订单。

直接在各租户库之间做联合查询或网关扇出，会让分页、延迟、权限和后续拆库都变差。

## Decision / 决策

1. 新增**用户订单索引/读模型**，作为 C 端“我的订单”的唯一查询入口：
   - 表建议：`ord_user_order_index`（`open_saas`）；
   - 唯一键：`(platform_user_id, order_type, order_id)`；
   - 字段：`platform_user_id`、`tenant_id`、`store_id`、`order_type`（HOTEL/KTV/OTHER）、`order_id`、`order_no`、`title`、`status`、`amount_minor`、`currency`、`cover_url`、`created_at`、`updated_at`；
   - 索引：`(platform_user_id, created_at desc)`。
2. 写模型仍是各订单表；索引由订单应用服务在创建/状态变更时维护，后续可切换为 Outbox 事件投影。
3. C 端接口：
   - `GET /api/v1/my/orders`：按当前登录用户的 `platformUserId` 查询，不要求 `X-Tenant-Context`；
   - `GET /api/v1/my/orders/{orderType}/{orderId}`：先校验索引归属，再按订单所属租户读取详情。
4. 平台用户身份使用全局 `platformUserId`；租户内 `customerId` 仍保留，作为业务归属。
5. 订单详情、退款、发票等写操作仍必须带订单所属租户上下文，不能在“我的订单”接口里跨租户写。

## Consequences / 后果

正面：

- C 端一次查询即可看到所有集团/门店订单；
- 不依赖网关扇出，分页和排序稳定；
- 为后续订单类型扩展提供统一入口。

代价与风险：

- 需要全局 `platformUserId` 与租户 `customerId` 的映射；
- 索引与订单表存在一致性窗口，需要补偿/对账；
- 索引包含跨租户信息，必须严格按当前用户鉴权，禁止管理员复用该接口；
- 历史订单需要回填。

后续动作：

- 先补 `platformUserId` 身份映射；
- 加索引回填任务和对账；
- 详情接口做归属校验和租户上下文恢复；
- 监控索引延迟/缺失。

## Alternatives / 备选方案

- **网关扇出到各租户**：拒绝。分页、延迟、部分失败不可控。
- **直接跨租户 SQL**：拒绝。破坏租户隔离，权限难审计。
- **每个业务各自提供“我的订单”**：拒绝。C 端会出现多个订单入口，与用户心智不符。

## References / 参考

- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [客户服务](../../50-services/platform/platform-customer-service/README.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
