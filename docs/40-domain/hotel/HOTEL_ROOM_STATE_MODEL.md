# 酒店房态、在住与清扫状态模型

本文是酒店前台房态卡的**状态语义唯一说明**。三套状态不能混用：

```text
预订/在住状态  booking/stay
房间物理状态    room
清扫任务状态    housekeeping task
```

## 1. 预订/在住状态

| 状态 | 含义 | 客人是否在店 | 房间物理状态 |
| --- | --- | --- | --- |
| `CONFIRMED` / `ARRIVAL` | 已确认/待入住 | 否 | 不应占用房间 |
| `IN_HOUSE` | 已入住、在住 | 是 | 必须 `OCCUPIED` |
| `DUE_OUT` | 已到离店日，但仍未办理退房 | **是，仍在店** | 必须 `OCCUPIED` |
| `CHECKED_OUT` | 已办理退房 | 否 | `DIRTY` 或清扫后 `ACTIVE` |

`DUE_OUT` 不是“已经退房”，而是酒店行业常说的 **Due Out / 今日离店待退房**：客人仍然在住，前台还需要结账、退房、收房卡。房态卡应显示“在住 · 今日离店”，不能显示成脏房。

## 2. 房间物理状态

| 状态 | 含义 | 可售 |
| --- | --- | --- |
| `ACTIVE` | 空闲、可售 | 是 |
| `OCCUPIED` | 有在住客人 | 否 |
| `DIRTY` | 客人已退房，等待退房清扫 | 否 |
| `MAINTENANCE` | 维修/停用 | 否 |

硬约束：

```text
存在 IN_HOUSE stay 的房间 -> OCCUPIED
CHECKED_OUT 后的房间     -> DIRTY + DEPARTURE 清扫任务
DEPARTURE 清扫完成        -> ACTIVE
```

## 3. 清扫任务

任务字段：

```text
task_type: STAYOVER | DEPARTURE
source:    CUSTOMER | FRONT_DESK | CHECKOUT
status:    PENDING | IN_PROGRESS | DONE
```

### STAYOVER：在住日常清扫

- 客人在住期间可以要求打扫；
- C 端调用接口：
  ```text
  POST /business/hotel/bookings/{id}/housekeeping-requests
  ```
- 只创建 `STAYOVER / CUSTOMER / PENDING` 任务；
- **绝不把 OCCUPIED 房间改成 DIRTY**；
- 任务完成后房间仍为 `OCCUPIED`，不影响客人继续住。

### DEPARTURE：退房清扫

- 前台办理退房时自动创建；
- 来源 `CHECKOUT`；
- 房间先置 `DIRTY`；
- 只有 `DEPARTURE` 任务完成后才执行 `DIRTY -> ACTIVE`；
- 换房时旧房间同样创建 `DEPARTURE / FRONT_DESK` 任务。

### 幂等

同一预订已有未完成的 `STAYOVER` 任务时，C 端重复申请返回现有任务，不重复创建。

## 4. 退房与账单

退房前必须满足：

```text
stay.status = IN_HOUSE
folio.paid_minor >= folio.charge_minor（或订单预付可抵扣）
```

余额未结清时返回 `409 OUTSTANDING_BALANCE`，这是保护规则，不是状态错误。前台应先完成收款/押金抵扣，再退房；退房成功后自动产生 `DEPARTURE` 清扫任务。

## 5. 状态流转

```text
CONFIRMED/ARRIVAL
  -> check-in
IN_HOUSE + room OCCUPIED
  -> 到离店日 / 夜审
DUE_OUT + room OCCUPIED（仍在住）
  -> checkout（账单结清）
CHECKED_OUT + room DIRTY + DEPARTURE task
  -> 完成 DEPARTURE 清扫
room ACTIVE

IN_HOUSE
  -> C 端申请 / 前台创建
STAYOVER task（room 仍 OCCUPIED）
  -> 完成 STAYOVER
room 仍 OCCUPIED
```

## 6. 前端展示规则

房态卡以**在住状态优先于物理房态**：

- 有 `IN_HOUSE` / `DUE_OUT` stay：显示住客、住宿区间、账务和“在住/今日离店”；
- 只有退房后的空房：显示 `DIRTY` 和退房清扫状态；
- 历史脏数据（在住但房间 DIRTY）由 V23 数据修复收敛，前端也不能把住客误显示成“待清扫”。

## 7. 相关实现

- `HotelStayApplicationService`：入住、退房、换房、账务；
- `HotelHousekeepingApplicationService`：STAYOVER/DEPARTURE 清扫；
- `HotelCustomerBookingController`：C 端在住清扫申请；
- 前端 `hotel.vue`：房态卡、详情抽屉、清扫任务列表。
