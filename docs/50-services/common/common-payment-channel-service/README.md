# common-payment-channel-service

[中文](README.md) | [English](README.en.md)

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 微信 / 支付宝 / Stripe 渠道适配（支付域·渠道）
- 创建渠道支付意图（create-intent）
- 渠道回调接收与查询（callback / query）
- 渠道可用能力查询（available）
- 渠道回调验签成功后调用 payment-service 内部结算接口（`/internal/payment/channel-settlement`，v2 内部签名）

## 端口

- 服务直连：`4180`（`server.port`）
- 网关公开入口：`/api/v1/admin/channels/**`（预留）

## 入口（controller 路径，不带 /api）

- `/internal/channels/available`（GET 可用渠道）
- `/internal/channels/{provider}/create-intent`（POST 创建意图）
- `/internal/channels/{provider}/callback`（POST 回调）
- `/internal/channels/{provider}/query`（POST 查询）

## 渠道回调结算桥（2.4.0 二期收口）

渠道服务只记录渠道交易与回调原文，**不拥有订单/支付台账**；验签通过后由结算桥调用 payment-service：

- 出站使用 SDK `InternalServiceSignature` 生成 v2 canonical HMAC headers（`X-IM-Service-*`），
  与 payment-service 的 `InternalServiceAuthenticationFilter` 同口径；source 为
  `common-payment-channel-service`（需在 payment 侧 expected-source 白名单内）。
- 配置：`app.payment-settlement.base-url`（默认 `http://common-payment-service:4140`，env
  `PAYMENT_SERVICE_BASE_URL`）与 `internal.service-auth.secret`（env `INTERNAL_SERVICE_AUTH_SECRET`，
  必须与 payment-service 一致；缺失时启动即失败，避免空密钥静默 401）。
- **成功 ack 只在结算被受理后返回**：结算调用失败（不可达 / 非 2xx / 签名配置缺失）时返回该渠道的
  失败 ack，让渠道按自身策略重投；重复投递由 payment-service 的结算幂等台账回放，不会重复记账。
- 失败不静默：记录 ERROR 日志，并把 `pay_channel_transaction.settlement_status` 置 `FAILED`、
  累加 `settlement_attempts`、写入 `settlement_error`（截断）与 `settlement_updated_at`，供检索与人工核对。

### SANDBOX 渠道（仅本地 Kind E2E）

本地没有真实商户密钥时，可用 SANDBOX 渠道真跑同一条回调结算链路（默认关闭，共享/生产不得启用）：

- 配置：`pay-channel-provider.sandbox.enabled`（env `SANDBOX_PAY_ENABLED`）与
  `webhook-secret`（env `SANDBOX_PAY_WEBHOOK_SECRET`）；本地 `k8s/local/saas.yaml` 已注入固定测试值。
- 回调：`POST /internal/channels/sandbox/callback`，Header `X-Sandbox-Signature` =
  `hex(HMAC-SHA256(rawBody, webhook-secret))`，JSON body：
  `{transactionId, eventId?, orderId, amount, currency, status, tenantId?}`；
  `status ∈ SUCCEEDED|REFUNDED|FAILED|CLOSED`。
- 验签通过后与真实渠道一样落 `pay_channel_transaction` 并触发 payment-service 内部结算，
  因此成功/失败/重复/退款回调都能被真实 Kind E2E 验证。

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_payment_channel`
- 渠道供应商 SDK：微信 / 支付宝 / Stripe（能力预留，线上默认关）
- 内部 HTTP：`common-payment-service`（渠道回调结算 `/internal/payment/channel-settlement`）
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
- 回调结算失败（`settlement_status=FAILED` / `settlement_attempts` 持续增长）

## 关联 ID

- 渠道交易号：与 `common-payment-service` 对账
- `X-IM-Service-Request-Id`：内部服务调用请求 ID
- `X-Tenant-Context`：租户上下文（`tenantId`）

## 故障处置

- 回调失败：核对验签配置与幂等去重
- 结算失败：核对 payment-service 可达性、`INTERNAL_SERVICE_AUTH_SECRET` 一致性、
  `settlement_error` 与 payment-service 日志；渠道重投后应转为 `APPLIED`
- 渠道超时：核对供应商网关与网络
- 对账不一致：核对渠道交易号与本地流水

## 状态

- 骨架已落库（E5）：渠道适配接口已实现，线上渠道默认关（见 `SAAS_PLATFORM_07_EXECUTION.md`）
