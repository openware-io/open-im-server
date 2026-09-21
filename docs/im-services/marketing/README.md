# platform-marketing-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 营销活动（campaign）与优惠券（coupon）
- 优惠券发放与核销
- 营销同意（consent）管理
- 优惠券核销事件 Outbox 投递

## 端口

- 服务直连：`4170`（`server.port`）
- 网关公开入口：`/api/v1/business/campaigns/**`、`/api/v1/business/coupons/**`、`/api/v1/marketing/**`

## 入口（controller 路径，不带 /api）

- `/business/campaigns`（GET 列表）
- `/business/coupons`（GET / POST）、`/business/coupons/{id}/issue`、`/business/coupons/{id}/redeem`
- `/business/marketing/consent`（GET / PUT）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_marketing`
- MQ：RocketMQ（`mkt_coupon_event_redeemed_v1`，Outbox Relay 生产 + 消费去重表）
- Redis：无

## 关键指标

- 优惠券发放 / 核销吞吐与错误率
- 核销事件 Outbox 积压与重试
- 核销去重命中次数

## 告警

- 核销失败或重复核销异常
- Outbox 投递积压 / 重试超限
- 优惠券库存 / 有效期校验异常

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId` / `storeId`）
- `couponId` / `campaignId`：优惠券与活动
- `eventId`：核销事件幂等键

## 故障处置

- 核销异常：核对去重表与优惠券状态
- 事件未投递：核对 `platform.marketing.outbox.*` 与 RocketMQ
- 发放失败：核对 `gv_saas` 优惠券表与 Flyway 迁移

## 状态

- 骨架已落库（E6）：活动 / 优惠券 / 同意接口已实现（见 `SAAS_PLATFORM_07_EXECUTION.md`）
