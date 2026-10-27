# common-payment-service

[中文](README.md) | [English](README.en.md)

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 支付意图与组合收款（现金先行，渠道后置）
- 退款申请
- 班次（shift）与日结（daily-closing）
- 对账摘要与渠道配置
- 收款 / 退款事件 Outbox 投递

## 端口

- 服务直连：`4140`（`server.port`）
- 网关公开入口：`/api/v1/business/orders/*/collect`、`/api/v1/business/shifts/**`、`/api/v1/business/refund-requests/**`、`/api/v1/admin/daily-closings/**`、`/api/v1/admin/reconciliations/**`、`/api/v1/admin/payment-channels/**`

## 入口（controller 路径，不带 /api）

- `/business/orders/{orderId}/collect`（组合收款）
- `/business/shifts/open`、`/business/shifts/{id}/close`
- `/business/refund-requests`（POST）
- `/admin/daily-closings/{id}/submit`、`/admin/reconciliations/summary`
- `/admin/payment-channels`（GET / POST 渠道配置）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_payment`
- MQ：RocketMQ（`pay_collect_event_confirmed_v1`、`pay_refund_event_requested_v1`，Outbox Relay 生产）
- 外部 HTTP：`common-payment-channel-service`（渠道 SPI，组合收款 / 回调 / 查询）
- Redis：无

## 订单交易状态落账（2.4.0）

组合收款与退款直接落 `ord_order` 的交易事实列（收口前仍双写兼容列 `status`）：

- 收款 `markPaid`：全额收款 `trade_status = PAID`，部分收款 `PENDING_PAYMENT`（金额看 `paid_amount`）；
  `status` 兼容列按“足额即 COMPLETED”双写；
- 退款 `markRefunded`：`refund_status` 退到已收归零为 `FULL`，否则 `PARTIAL`（赋值先于 `paid_amount` 回冲，
  避免用已回冲金额误判全额）；
- 守卫以 `trade_status` 排除 `CANCELLED` / `VOIDED` / `COMPLETED`；取消与作废分开统计，
  作废单不再参与收款/退款落账。

订单主表契约见
[订单/房态/房量/商品库存目标契约](../../../40-domain/architecture/ORDER_ROOM_INVENTORY_TARGET.md)。

## 关键指标

- 收款 / 退款吞吐与错误率
- 抵扣拆分（优惠 → 积分 → 储值 → 现金）成功率
- Outbox 投递积压与重试

## 告警

- 收款重复记账或金额不一致
- Outbox 投递积压 / 重试超限
- 渠道回调异常 / 乱序 / 重放

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId` / `storeId`）
- `orderId`：收款关联订单
- 支付流水号 / 渠道交易号：对账与回调
- `eventId`：Outbox 事件幂等键

## 故障处置

- 收款失败：核对同库同事务抵扣拆分与余额校验
- 回调异常：核对渠道验签与幂等去重
- 日结不可追溯：核对订单 / 渠道交易 / 班次关联

## 状态

- 骨架已落库（E5）：组合收款 / 渠道默认关 / 对账摘要已实现；线上渠道验签与对账文件比对后置（见 `SAAS_PLATFORM_07_EXECUTION.md`）
