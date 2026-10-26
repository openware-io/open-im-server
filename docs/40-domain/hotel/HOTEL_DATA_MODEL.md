# 酒店域数据模型

> 状态：`accepted`（2026-10-23，`develop/2.4.0-hotel`）
> 归属：`platform-order-service` 的 `domain/hotel`（ADR-0008）。
> 金额：整数最小货币单位；禁止浮点。
> 隔离：所有业务表带 `tenant_id`、`store_id`，查询由 `TenantContext` 强制。

## 表清单

```text
htl_hotel                 酒店资料（一个门店可有一个酒店）
htl_room_type             房型
htl_room                  物理房间
htl_rate_plan             房价计划
htl_daily_rate            每日房价
htl_inventory_calendar    每日库存（room_type + stay_date）
htl_inventory_hold        库存占用（预订/支付暂持）
htl_booking               预订头
htl_booking_night         按夜价格与状态
htl_stay                  在住记录（入住/退房）
htl_folio                 账单头
htl_folio_item            账单项（ROOM/DEPOSIT/REFUND，退款必须带 method=CASH|ONLINE）
htl_housekeeping_task     客房清扫任务
htl_policy                店铺策略（支付/押金/取消/积分开关；一个酒店一条）
```

## 关键唯一键

```text
htl_hotel              (tenant_id, store_id)
htl_room_type          (tenant_id, store_id, code)
htl_room               (tenant_id, store_id, room_no)
htl_rate_plan          (tenant_id, store_id, code)
htl_daily_rate         (room_type_id, rate_plan_id, stay_date)
htl_inventory_calendar (room_type_id, stay_date)
htl_booking            (tenant_id, store_id, booking_no)
htl_booking_night      (booking_id, stay_date)
```

## 库存不变量

```text
available = total - sold - held
available >= 0
hold 必须过期或释放，不能永久占用
预订确认：hold -> sold
取消/超时/no-show：释放 hold 或回滚 sold
```

并发抢最后一间：

```text
UPDATE ... SET sold = sold + n
WHERE room_type_id = ? AND stay_date = ? AND total - sold - held >= n
```

受影响行数为 0 即库存不足；必须返回 409，不允许先查后写。

## 预订状态机

```text
PENDING_PAYMENT -> CONFIRMED -> ARRIVAL -> IN_HOUSE -> DUE_OUT -> CHECKED_OUT
PENDING_DEPOSIT -> CONFIRMED
PENDING_PAYMENT -> CANCELLED
PENDING_DEPOSIT -> CANCELLED
CONFIRMED       -> CANCELLED
CONFIRMED       -> NO_SHOW
```

- 支付确认必须幂等。
- 取消必须幂等，并释放未消费库存。
- 入住必须绑定物理房间。
- 退房必须结清账单或明确挂账。

## 租户与 PII

- 住客手机号、证件号按 PII 处理，接口默认脱敏。
- 前台完整查看需要 `hotel.guest.pii.view`。
- OTA/渠道凭证只存 Secret 引用，不落明文。
- 所有跨租户访问返回 404/403，不泄露存在性。

## 与共享域边界

```text
platform-order-service   酒店容量、房价、库存、预订、入住/退房
common-payment-service   收款、退款、对账
platform-customer-service 客户、积分
platform-resource-service 只保留 KTV 资源/占用，不承载酒店容量
common-audit-service     酒店/房价/库存/预订/入住/退房审计
```

## 迁移顺序

```text
H1 酒店/房型/房间/房价/每日库存
H2 报价/预订/支付确认/取消
H3 入住/退房/押金/账单/退款
H4 夜审/客房清扫/报表
```
