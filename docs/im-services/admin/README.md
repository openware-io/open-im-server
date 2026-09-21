# platform-admin-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 平台运营 / 租户 Admin BFF（不拥有领域表）
- 菜单与后端能力（backend）聚合
- KTV 运营配置：套餐 / 支付开关 / 服务目录 / 钱包充值
- 平台 / 租户上下文解析与领域客户端

## 端口

- 服务直连：`4150`（`server.port`）
- 网关公开入口：`/api/v1/admin/menus/**`、`/api/v1/admin/backends/**`

## 入口（controller 路径，不带 /api）

- `/admin/menus`（GET 菜单）
- `/admin/backends`（GET 后端能力）
- `/admin/ktv/pricing-plans`、`/admin/ktv/payment-switches`、`/admin/ktv/server-catalog`
  （储值不在本 BFF：`/admin/ktv/wallet-recharge` 已于 2026-09-19 随「储值管理」合并删除，
  储值是租户级资产，唯一入口是租户后台「储值管理」页，直连 customer 域 `/admin/wallets/*`
  与 `/business/members/{id}/wallet[/ledger]`）
- `/admin/reservations/**`（预约 BFF，转发 order 域）：列表/确认/到店/取消/未到店/分配包厢/开台，
  以及只读的 `GET /admin/reservations/{id}/assignable-rooms`（分配包厢候选：房态 + 本时段预约冲突，
  由 order 域算好，后台弹窗不再自己拼资源列表）

## 依赖

- MySQL：无自有库（BFF，禁止新增领域 Mapper / 表）
- 外部 HTTP：调用各领域服务（identity / tenant / resource / order / payment / customer / marketing）
- Redis：无
- MQ：无

## 关键指标

- 菜单 / 后端能力查询时延与错误率
- 下游领域服务调用错误率
- KTV 配置读写吞吐

## 告警

- 下游领域服务不可达（BFF 整体降级）
- 配置读写错误率升高
- 鉴权上下文缺失导致的拒绝率异常

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId` / `storeId` / `accountId`）
- `X-IM-Service-Request-Id`：内部服务调用请求 ID

## 故障处置

- 菜单 / 配置异常：核对对应下游领域服务健康与数据
- 上下文缺失：核对 Gateway 透传与 IAM 上下文
- 配置写失败：核对目标领域服务（如 tenant / order）可达性

## 状态

- 骨架已落库（E2）：菜单 / 后端能力 / KTV 配置 BFF 已实现，不拥有领域表（见 `SAAS_PLATFORM_07_EXECUTION.md`）
