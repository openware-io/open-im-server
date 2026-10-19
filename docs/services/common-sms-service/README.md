# common-sms-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 短信发送（供应商可配置：阿里云 / 腾讯云等）
- 短信配置管理（管理端）

## 端口

- 服务直连：`4200`（`server.port`）
- 网关公开入口：无（仅内部调用）

## 入口（controller 路径，不带 /api）

- `/internal/sms/send`（POST 发送）
- `/admin/sms/config`（GET 配置）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_sms`
- 短信供应商 SDK：阿里云 / 腾讯云（可配置）
- Redis：无
- MQ：无

## 关键指标

- 短信发送吞吐与成功率
- 供应商回执失败次数
- 发送队列积压

## 告警

- 发送成功率下降（供应商 / 模板 / 签名异常）
- 供应商不可达或额度耗尽
- 配置加载失败

## 关联 ID

- `X-Tenant-Context`：租户上下文（`tenantId`）
- 短信批次号 / 供应商回执 ID
- `X-IM-Service-Request-Id`：内部调用请求 ID

## 故障处置

- 发送失败：核对供应商配置、模板与签名
- 回执异常：核对供应商回调与重试
- 配置缺失：核对 `gv_saas` 配置表与 Flyway 迁移

## 状态

- 骨架已落库：发送 / 配置接口已实现；未接 springdoc（无 OpenAPI 快照）（见 `SAAS_PLATFORM_07_EXECUTION.md`）
