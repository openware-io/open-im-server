# 酒店房态、在住与清扫状态模型

本文是酒店前台房态卡的**状态语义唯一说明**。状态拆成三组，不能互相替代：

```text
预订/在住状态  booking
房间状态      room（占用 / 清扫 / 不可售）
清扫任务状态  housekeeping task
```

## 1. 预订/在住状态

`htl_booking.status` 只表达预订/在住事实：

| 状态 | 含义 | 客人是否在店 | 房间占用 |
| --- | --- | --- | --- |
| `PENDING_CONFIRM` | 待确认（含到店付/定金待确认） | 否 | 不占具体房间 |
| `CONFIRMED` | 已确认、待到店 | 否 | 不占具体房间 |
| `CHECKED_IN` | 已入住、在住 | 是 | 必须 `OCCUPIED` |
| `DUE_OUT` | 已到离店日，仍未退房 | **是，仍在店** | 必须 `OCCUPIED` |
| `CHECKED_OUT` | 已退房 | 否 | `VACANT + DIRTY` |
| `CANCELLED` | 已取消 | 否 | 释放房量 |
| `NO_SHOW` | 未到店 | 否 | 释放房量 |

`DUE_OUT` 不是已退房；前台仍需结账、退房、收房卡。

## 2. 房间状态（三组字段）

`htl_room` 不再用单一 `status`：

```text
occupancy_status       VACANT | OCCUPIED
housekeeping_status    CLEAN | DIRTY | CLEANING | INSPECTED
out_of_order_status    NONE | OUT_OF_ORDER | OUT_OF_SERVICE
```

业务保留/锁定在 `htl_room_hold`：

```text
reason: RESERVED | GROUP_BLOCK | HOUSE_USE | MAINTENANCE | OUT_OF_SERVICE | LOCKED
status: ACTIVE | RELEASED
```

前台派生展示态固定为：`空闲`、`已入住`、`待清扫`、`已预留`、`维修中`。

硬约束：

```text
存在 CHECKED_IN/DUE_OUT 在住 -> occupancy_status = OCCUPIED
退房/换房旧房               -> occupancy_status = VACANT, housekeeping_status = DIRTY
DEPARTURE 清扫完成           -> housekeeping_status = CLEAN
脏房 / 维修房 / 有效保留房   -> 不能入住（409 ROOM_UNAVAILABLE）
```

## 3. 房量（不是商品库存）

```text
htl_room_availability
  tenant_id + store_id + room_type_id + stay_date
  total / sold / held
htl_room_availability_hold
  booking_id + stay_date
  status: HELD | SOLD | RELEASED
```

规则：

- 预订只占用房量，不分配具体房间；
- 入住才分配房间，入住**不二次扣房量**；
- 退房**不改房量**；
- 维修/停售按日期调整 `total`，恢复时加回；`total >= sold + held`；
- `held` 与有效 hold 账本一致，禁止永久占用。

## 4. 清扫任务

```text
task_type: STAYOVER | DEPARTURE
source:    CUSTOMER | FRONT_DESK | CHECKOUT
status:    PENDING | IN_PROGRESS | DONE
```

- 在住日常清扫只创建 `STAYOVER` 任务，不把 `OCCUPIED` 改成 `DIRTY`；
- 退房自动创建 `DEPARTURE`，房间先 `DIRTY`，任务完成后 `CLEAN`；
- 换房时旧房同样创建 `DEPARTURE` 任务。

## 5. 退房与账单

退房前必须满足：

```text
booking.status = CHECKED_IN 或 DUE_OUT
账单已结清（或订单预付可抵扣）
```

账单权限使用 `hotel.folio.view` / `hotel.folio.manage`；房态使用 `hotel.room.view` / `hotel.room.manage`；清扫使用 `hotel.housekeeping.view` / `hotel.housekeeping.manage`。
