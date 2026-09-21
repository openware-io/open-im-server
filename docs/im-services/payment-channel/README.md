# common-payment-channel-service

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 微信 / 支付宝 / Stripe 渠道适配（支付域·渠道）
- 创建渠道支付意图（create-intent）
- 渠道回调接收与查询（callback / query）
- 渠道可用能力查询（available）

## 端口

- 服务直连：`4180`（`server.port`）
- 网关公开入口：`/api/v1/admin/channels/**`（预留）

## 入口（controller 路径，不带 /api）

- `/internal/channels/available`（GET 可用渠道）
- `/internal/channels/{provider}/create-intent`（POST 创建意图）
- `/internal/channels/{provider}/callback`（POST 回调）
- `/internal/channels/{provider}/query`（POST 查询）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_payment_channel`
- 渠道供应商 SDK：微信 / 支付宝 / Stripe（能力预留，线上默认关）
- Redis：无
- MQ：无

## 关键指标

- 渠道意图创建吞吐与错误率
- 回调验签失败 / 重放拒绝次数
- 渠道查询超时与重试

## 告警

- 渠道回调验签失败率升高（疑似密钥 / 重放攻击）
- 渠道供应商不可达或超时
- 意图创建与查询不一致

## 关联 ID

- 渠道交易号：与 `common-payment-service` 对账
- `X-IM-Service-Request-Id`：内部服务调用请求 ID
- `X-Tenant-Context`：租户上下文（`tenantId`）

## 故障处置

- 回调失败：核对验签配置与幂等去重
- 渠道超时：核对供应商网关与网络
- 对账不一致：核对渠道交易号与本地流水

## 状态

- 骨架已落库（E5）：渠道适配接口已实现，线上渠道默认关（见 `SAAS_PLATFORM_07_EXECUTION.md`）
