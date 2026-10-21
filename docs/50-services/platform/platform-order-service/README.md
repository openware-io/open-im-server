# platform-order-service

[中文](README.md) | [English](README.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 运行时端口：`4130`  
> 数据归属：MySQL `open_saas` 的 `ord_*` 表

## 职责

订单与预约域的写模型和编排服务：

- 订单：创建、确认、取消、作废、结算、收款状态衔接；
- 预约：确认、到店、未到店、取消、开台兑现；
- KTV 会话：开台、暂停/恢复、结台、计时与包厢费；
- 订单明细、账单、商品目录与库存占用；
- 通过 Outbox 发布订单/结算等事件。

## 边界

- 拥有 `ord_*` 订单/预约/会话/账单/商品/库存表；
- 不拥有客户、会员、房间资源、支付流水；通过客户/资源/支付/审计服务协作；
- 不直接写其他服务的表；跨域只传 ID 或事件。

## 入口与依赖

| 项 | 说明 |
| --- | --- |
| HTTP | Gateway 路由到 `platform-order-service:4130` |
| 数据库 | MySQL `open_saas` |
| 消息 | RocketMQ；Outbox Relay 发布订单事件 |
| 下游 | 客户、资源、支付、审计 |

## 关键状态

订单、预约、KTV 会话的状态与迁移见：

- [订单/预约/开台状态机](../../../20-architecture/diagrams/08-order-reservation-state.md)
- [领域文档](../../../40-domain/README.md)

## 相关文档

- [服务目录](../README.md)
- [API 契约](../../../30-reference/contracts/README.md)
- [数据库迁移标准](../../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
