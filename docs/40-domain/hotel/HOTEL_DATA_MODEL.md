# 酒店域数据模型

> 状态：`current`（2026-10，领域重构后）
> 归属：`platform-order-service` 的 `domain/hotel`（ADR-0008）。
> 金额：整数最小货币单位；禁止浮点。
> 隔离：业务表带 `tenant_id`、`store_id`、`organization_id`；`organization_id = store.organization_id`，`business_type = store.business_type`。

## 表清单

```text
htl_hotel                     酒店资料（一个门店可有一个酒店）
htl_room_type                 房型
htl_room                      物理房间（占用/清扫/不可售三组状态）
htl_room_hold                 客房保留/锁房/维修占用
htl_rate_plan                 房价计划
htl_daily_rate                每日房价
htl_room_availability         房量（房型 × 日期：total/sold/held）
htl_room_availability_hold    房量占用账本
htl_booking                   预订头（order_id 唯一）
htl_booking_night             按夜价格与状态
htl_stay                      在住记录
htl_folio                     账单头
htl_folio_item                账单项
htl_housekeeping_task         客房清扫任务
htl_policy                    店铺策略
```

`htl_inventory_calendar` / `htl_inventory_hold` 已废弃；房量不是商品库存，商品库存统一走通用 Inventory 域。

## 关键唯一键

```text
htl_hotel                    (tenant_id, store_id)
htl_room_type                (tenant_id, store_id, code)
htl_room                     (tenant_id, store_id, room_no)
htl_room_availability        (tenant_id, store_id, room_type_id, stay_date)
htl_room_availability_hold   (booking_id, stay_date)
htl_booking                  (tenant_id, store_id, booking_no)
htl_booking.order_id         UNIQUE
htl_room_hold                (tenant_id, store_id, room_id, status)
```

`organization_id` 是归属/报表快照，不进入房量唯一键。

## 房量不变量

```text
available = total - sold - held
available >= 0
held = Σ ACTIVE hold.rooms
hold 必须过期或释放，不能永久占用
预订确认：hold -> sold
取消/超时/no-show：释放 hold 或回滚 sold
维修/停售：total 按日期 ±1，且 total >= sold + held
入住/退房不改房量
```

并发抢最后一间：

```text
UPDATE htl_room_availability
SET sold = sold + n
WHERE tenant_id = ? AND store_id = ? AND room_type_id = ? AND stay_date = ?
  AND total - sold - held >= n
```

受影响行数为 0 即房量不足；必须返回 409，不允许先查后写。

## 预订状态机

```text
PENDING_CONFIRM -> CONFIRMED -> CHECKED_IN -> DUE_OUT -> CHECKED_OUT
PENDING_CONFIRM/CONFIRMED -> CANCELLED
PENDING_CONFIRM/CONFIRMED -> NO_SHOW
```

`htl_booking.status` 只表达预订/在住；订单交易状态在 `ord_order.trade_status`；KTV 会话状态在 `ord_ktv_session.status`。
