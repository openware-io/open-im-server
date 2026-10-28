# common-payment-service

[中文](README.md) | [English](README.en.md)

> E0 服务文档基线。职责/端口/入口按当前脚手架实现填写，指标/告警阈值待可观测体系接入后校准。

## 职责

- 支付意图与组合收款（现金先行，渠道后置）
- 退款申请
- 班次（shift）与日结（daily-closing）
- 对账摘要与渠道配置
- 收款 / 退款事件 Outbox 投递
- 渠道回调幂等结算（渠道验签成功后落 pay_intent/pay_transaction 并把收款/退款同步到订单）

## 端口

- 服务直连：`4140`（`server.port`）
- 网关公开入口：`/api/v1/business/orders/*/collect`、`/api/v1/business/shifts/**`、`/api/v1/business/refund-requests/**`、`/api/v1/admin/daily-closings/**`、`/api/v1/admin/reconciliations/**`、`/api/v1/admin/payment-channels/**`

## 入口（controller 路径，不带 /api）

- `/business/orders/{orderId}/collect`（组合收款）
- `/business/shifts/open`、`/business/shifts/{id}/close`
- `/business/refund-requests`（POST）
- `/admin/daily-closings/{id}/submit`、`/admin/reconciliations/summary`
- `/admin/payment-channels`（GET / POST 渠道配置）
- `/internal/payment/channel-settlement`（POST 渠道回调幂等结算；仅内部 HMAC v2 可调，网关不外暴露）

## 依赖

- MySQL：库 `gv_saas`，Flyway history 表 `flyway_schema_history_payment`
- MQ：RocketMQ（`pay_collect_event_confirmed_v1`、`pay_refund_event_requested_v1`，Outbox Relay 生产）
- 外部 HTTP：`common-payment-channel-service`（渠道 SPI，组合收款 / 回调 / 查询）
- 内部被调：`common-payment-channel-service` 在渠道回调验签成功后调用 `/internal/payment/channel-settlement`
- Redis：无

## 渠道回调幂等结算（2.4.0 二期收口）

渠道服务只拥有渠道交易与回调原文；真正落账回到本服务，避免渠道回调"验签成功但订单永远未收款"：

- 入参：`provider` / `transactionId` / `eventId` / `tenantId` / `storeId` / `orderId` /
  `amountMinor` / `currency` / `status` / `refundAmount`（内部 HMAC v2 签名，`/internal/payment/**`）。
- **成功回调**（`SUCCEEDED`）：同一本地事务内落 `pay_intent`(SUCCEEDED) + `pay_transaction`(SUCCEEDED)，
  再以 CAS（期望已收金额）调用 `OrderBillingMapper.markPaid` 推进 `ord_order.trade_status/paid_amount/refundable_amount`；
  任一步失败整体回滚。
- **幂等**：`pay_channel_settlement` 以 `(provider, transaction_id, kind)` 唯一键记录结算事实；
  重复回调（含不同 `eventId` 的同一渠道交易）命中即回放首次结果，返回 `replayed=true`，不再重复记账。
- **失败 / 关闭 / 处理中**（`FAILED` / `CLOSED` / `PROCESSING`）：不改订单收款、不写资金流水，返回
  `applied=false, settlementType=NONE`；渠道可安全重投。
- **退款成功**（`REFUNDED`）：按 `refundAmount`（缺省等于 `amountMinor`）回冲 `paid_amount/refundable_amount`
  并置 `refund_status`（`FULL`/`PARTIAL`），同样以结算台账幂等。
- **拒绝口径**：租户/门店与订单不一致 → 422 `CROSS_TENANT_SCOPE` / `CROSS_STORE_SCOPE`；
  币种与订单快照不一致 → 422 `CURRENCY_MISMATCH`；收款金额 ≠ 订单剩余应收 → 422 `PAYMENT_AMOUNT_MISMATCH`；
  退款超过已收 → 422 `REFUND_AMOUNT_EXCEEDS_PAID`；订单已被其他收款改写 → 409 `PAYMENT_ORDER_CONFLICT`。
- 金额口径：`amountMinor` 是最小货币单位整数，与 `pay_intent/pay_transaction/ord_order` 现有金额列口径一致。

## 退款幂等与错误码（2.4.0 收口 must-fix）

退款申请/线下退款登记此前在重复提交时分别会"新建第二条 refund"与"抛 IllegalStateException → 500"，收口后：

- **申请退款**（`POST /business/refund-requests`）支持可选 `Idempotency-Key`：
  - 同键重放返回首次创建的退款单（200、同 id、不新建）；
  - 同键不同订单/金额 → 409 `IDEMPOTENCY_KEY_REUSED`；
  - 不带键（或换键）但同订单 + 同申请金额已有未终结（`PENDING`/`APPROVED`）退款单 → 409 `REFUND_DUPLICATE`，DB 不新增行。
- **线下退款登记**（`POST /admin/refund-requests/{id}/refund`）重复调用：状态 CAS 失败返回 409
  `REFUND_STATE_INVALID`（不再是 500），且不会二次回冲 `ord_order.paid_amount/refundable_amount/refund_status`。
- 其余退款业务错误同样使用显式 4xx：`REFUND_NOT_FOUND`(404)、`REFUND_AMOUNT_INVALID`(422)、
  `REFUND_AMOUNT_EXCEEDS_REQUEST`(422)、`REFUND_ORDER_CONFLICT`(409)。
- 渠道退款回调（`/internal/payment/channel-settlement` 的 `REFUNDED`）走 `pay_channel_settlement` 幂等台账，
  与本节的线下退款状态机互不影响。

## 订单交易状态落账（2.4.0）

组合收款、退款与渠道回调结算直接落 `ord_order` 的交易事实列（兼容列 `status` 已在收口迁移 V27 删除）：

- 收款 `markPaid`：全额收款 `trade_status = PAID`，部分收款 `PENDING_PAYMENT`（金额看 `paid_amount`）；
  只写 `trade_status`，不再双写 `status`；
- 退款 `markRefunded`：`refund_status` 退到已收归零为 `FULL`，否则 `PARTIAL`（赋值先于 `paid_amount` 回冲，
  避免用已回冲金额误判全额）；
- 守卫以 `trade_status` 排除 `CANCELLED` / `VOIDED` / `COMPLETED`；取消与作废分开统计，
  作废单不再参与收款/退款落账。
- 渠道回调结算与组合收款共用同一组落账方法（`markPaid` / `markRefunded`），因此订单状态口径一致；
  渠道回调不新增第二套订单状态。

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
- 渠道回调结算失败（`pay_channel_transaction.settlement_status=FAILED`）持续存在

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
