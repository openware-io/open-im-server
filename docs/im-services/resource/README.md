# platform-resource-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 包厢（首发）资源建模与排班
- 资源占用与冲突控制（`HELD/RESERVED/IN_USE/RELEASED`）
- 资源看板与管理接口

## 端口

- 服务直连：`4120`（`server.port`）
- 网关公开入口：`/api/v1/admin/resources/**`、`/api/v1/business/resources/**`

## 入口（controller 路径，不带 /api）

- `/admin/resources`（POST 创建资源）
- `/business/resources/{resourceId}/occupations`（POST 占用）
- `/internal/resources`（GET 内部资源查询）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_resource`
- Redis：无
- MQ：无

## 关键指标

- 占用创建吞吐 / 冲突拒绝次数
- 占用状态转换时延
- 资源可用数量（按门店）

## 告警

- 同一资源 / 时段并发冲突异常升高（锁失效风险）
- 占用释放任务积压或失败
- 资源写操作错误率升高

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId` / `storeId`）
- `resourceId`：资源主键
- `occupationId`：占用记录主键

## 故障处置

- 冲突异常：核对占用状态机与事务锁实现
- 释放失败：检查过期释放任务与 Outbox 补偿
- 资源不可见：核对 `gv_saas` 资源表与 Flyway 迁移

## 状态

- 骨架已落库（E3 前置）：资源 / 占用接口已实现（见 `SAAS_PLATFORM_07_EXECUTION.md`）
