# 订单平台域（Order Kernel）

[中文](README.md) | [English](README.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 定位：订单交易内核是公共支撑域；预约/入住/开台等履约语义归各业务核心。  
> 原则：共享订单头/明细/金额/支付/基础状态，不共享一张万能订单表。

## 1. 领域分类

| 类型 | 例子 | 归属 |
| --- | --- | --- |
| 核心域 | 酒店预订/房态/价格、KTV 运营、饭店翻台 | 各业务核心 |
| 支撑域 | 订单交易内核、Stock、支付、客户、审计 | 公共平台 |
| 通用域 | 身份、消息、文件 | 通用能力 |

订单本质是**交易单据**，可以共享内核；但订单的履约语义因业务不同而不同。

## 2. 共享什么

```text
ord_order
  orderNo / tenantId / storeId / customerId / platformUserId
  orderType / businessType
  status / paymentStatus / fulfillmentStatus
  amountMinor / currency
  source / idempotencyKey / createdAt / updatedAt

ord_order_line
  orderId / lineNo / itemType / itemRef
  title / quantity / unitPriceMinor / amountMinor
```

共享能力：

- 订单号、幂等键；
- 金额、币种、明细；
- 支付状态、退款状态；
- 基础生命周期；
- 租户/门店隔离；
- 审计、Outbox、C 端订单索引；
- 权限与查询。

## 3. 分开什么

业务扩展表/模块：

```text
HotelBookingDetail
  hotelId / roomTypeId / ratePlanId / checkIn / checkOut
  roomCount / guestCount / stayDeposit...

KtvOrderDetail
  resourceId / sessionId / packageId / serviceStaff...

RestaurantOrderDetail
  tableId / diningSlot / turn...

GymOrderDetail
  classId / venueId / memberId...
```

规则：

- 公共订单头不出现 `roomTypeId`、`resourceId`、`tableId` 等业务列；
- 业务字段放扩展表，1:1 关联订单；
- 业务状态放 `fulfillmentStatus` 或扩展表，不塞进公共枚举；
- 预约单是订单类型/履约类型，不是另一套订单系统。

## 4. 订单类型与业务类型

```text
orderType     交易形态：SALE / RESERVATION / BOOKING / SUBSCRIPTION
businessType  业务归属：HOTEL / KTV / RESTAURANT / GYM / RETAIL
```

例子：

| 订单 | orderType | businessType |
| --- | --- | --- |
| 酒店预订 | BOOKING | HOTEL |
| KTV 预约 | RESERVATION | KTV |
| KTV 现场开台 | SALE / SESSION | KTV |
| 饭店餐位预订 | RESERVATION | RESTAURANT |
| 健身课程购买 | SALE | GYM |

`orderType` 决定交易流程，`businessType` 决定履约规则；两者不能混成一个字段。

## 5. 状态模型

公共状态只表达交易：

```text
DRAFT
PENDING_PAYMENT
PAID
CONFIRMED
COMPLETED
CANCELLED
REFUNDING
REFUNDED
```

履约状态由业务扩展：

```text
酒店：PENDING_ARRIVAL / CHECKED_IN / CHECKED_OUT / NO_SHOW
KTV：RESERVED / OPEN / CLOSED / CANCELLED
饭店：RESERVED / SEATED / FINISHED / NO_SHOW
健身：BOOKED / ATTENDED / NO_SHOW
```

不要在一个 `status` 枚举里塞所有业务状态。

## 6. 与现有 KTV 订单/预约的关系

现有 KTV：

```text
Reservation（预约）
Order（订单）
KtvSession（开台会话）
```

不建议一次性重写。采用兼容策略：

- 公共订单内核先服务酒店；
- KTV 通过适配层映射到公共订单头；
- `Reservation` 保留为 KTV 履约扩展，后续再评估合并；
- 不做大爆炸迁移。

## 7. 依赖关系

```text
Hotel / KTV / Restaurant / Gym
        │ 创建/支付/查询订单
        ▼
Order Kernel（公共支撑）
        │ 支付/退款
        ▼
Payment Service
```

- Order Kernel 不依赖具体业务域；
- 业务域通过 ACL 创建订单并写扩展；
- 业务域不直接写 `ord_order` 以外的共享表；
- 支付服务不感知酒店/KTV 业务字段。

## 8. 数据隔离与权限

- 所有订单带 `tenant_id + store_id`；
- 业务域只能访问自己门店的订单；
- 后端从会话解析上下文，不信任前端；
- C 端“我的订单”按 `platformUserId` 走统一索引；
- 后台按门店/权限过滤；
- 跨租户/门店访问 403/404。

## 9. 反模式

- 每个业务一套订单表；
- 一张订单表包含所有业务字段；
- 把所有业务状态塞进一个枚举；
- 预约单和订单两套系统；
- Order Kernel 反向依赖酒店/KTV；
- 业务域直接写公共订单表。

## 10. 相关文档

- [ADR-0012：订单作为共享交易内核](../../20-architecture/adr/0012-order-kernel.md)
- [酒店实现方案](../hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
