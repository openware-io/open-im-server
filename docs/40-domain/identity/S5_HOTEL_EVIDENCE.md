# S5 酒店门店与 C/B 应用证据

> 环境：Kind `open-im-local`，tenant 100
> 时间：2026-10-24
> 状态：进行中，C 预订 → B 前台确认已通；支付/退款/夜审待验证

## 1. HOTEL 门店

原酒店域位于 KTV 门店 100。新建：

```text
tnt_store id=102
code=a380-hotel-001
name=A380 Hotel
business_type=HOTEL
tenant_id=100 / organization_id=100
```

酒店域 `htl_*` 的 store_id 已从 100 迁到 102：

```text
htl_hotel / room_type / rate_plan / room / inventory_calendar
htl_booking / booking_night / stay / folio / folio_item / refund
htl_housekeeping_task / night_audit / inventory_hold
```

核对：

```text
htl_hotel id=1 store_id=102
htl_room_type id=1 store_id=102
```

## 2. 应用

租户安装：

```text
saas-hotel-c C ACTIVE
saas-hotel-b B ACTIVE
```

C 用户授权：

```text
tnt_app_user_authorization account_id=112 app_id=saas-hotel-c tenant_id=100 ACTIVE
```

服务入口：

```text
GET /auth/apps?appId=saas-a380-c -> [saas-hotel-c HOTEL]
GET /auth/apps?appId=saas-a380-h5 -> [saas-hotel-b HOTEL]
```

## 3. C 端酒店链路

C 会话选择：

```text
contextId=consumer:saas-a380-c:100:100:102
C token storeId=102
permissions 含 hotel.booking.view/create
```

目录：

```text
GET /api/v1/business/hotel/catalog/hotels
-> [{"id":1,"storeId":102,"name":"A380 Hotel"}]
```

报价：

```text
POST /api/v1/business/hotel/quotes
{"roomTypeId":1,"ratePlanId":1,"checkInDate":"2026-11-01","checkOutDate":"2026-11-03","rooms":1}
-> available=true totalMinor=179800 CNY
```

预订：

```text
POST /api/v1/business/hotel/bookings
-> 201 bookingNo=HTL1792808757297843
   id=2113819252491505665
   orderId=8
   status=PENDING_PAYMENT
   storeId=102
```

## 4. B 前台确认

B 选择 HOTEL 门店上下文：

```text
contextId=100:0:102
```

确认：

```text
POST /admin/hotel/bookings/2113819252491505665/confirm
-> 200 status=CONFIRMED storeId=102
```

## 5. 未完成

```text
C 端支付
退款（现金/在线）
前台入住/退房
夜审/房态
浏览器 B Web/C H5 页面
Android 真机
```

本证据不能作为 S5 完成或 E2E 通过。

## 6. 前台/夜审/房态真实证据（2026-10-24）

入住：

```text
POST /admin/hotel/frontdesk/bookings/2113819252491505665/checkin
-> 200 status=IN_HOUSE roomNo=0801
```

收款：

```text
POST /api/v1/business/orders/8/collect
{"currencyCode":"CNY","payable":179800,"payments":[{"method":"CASH","amount":179800}]}
-> 200 remainingAmount=0 collected CASH 179800
```

押金/账单：

```text
POST .../deposit {"amountMinor":179800}
-> 200 folio paidMinor=179800 chargeMinor=179800
```

退房：

```text
POST .../checkout
-> 200 status=CHECKED_OUT roomNo=0801
```

夜审：

```text
POST /admin/hotel/night-audit/run {"businessDate":"2026-11-01"}
-> 200 status=COMPLETED dueOutCount=1 inHouseCount=1 roomChargeMinor=179800
```

清扫：

```text
GET  /admin/hotel/housekeeping/tasks
-> task id=2 PENDING room 0801
POST .../tasks/2/start   -> 200 IN_PROGRESS
POST .../tasks/2/complete -> 200 DONE
```

## 7. 仍未完成

```text
C 端在线支付（当前由 B 前台现金收款）
现金/在线退款真实链路
浏览器 B Web / C H5 页面
Android 真机
S5 仲裁
```

## 8. 现金退款真实证据（2026-10-24）

第二笔 C 预订：

```text
booking id=2113821115177717762
orderId=9
-> B confirm 200
-> B checkin 200
-> deposit 179800 -> folio paid 179800
-> cash refund 10000 -> folio paid 169800
```

退款后直接退房：

```text
409 OUTSTANDING_BALANCE
```

补收 20000 后：

```text
-> 200 CHECKED_OUT
```

说明：退款减少 folio 已收，账单仍有应收时退房被正确拦截；补收后余额为 0 才允许退房。

## 9. C 订单归属隔离（2026-10-24）

V14：

```text
htl_booking.booked_by_account_id
index (store_id, booked_by_account_id)
```

C 下单写入 `accountId=112`：

```text
booking id=2113822455492558850 booked_by_account_id=112
```

C 我的订单：

```text
GET /business/hotel/bookings
-> 仅 id=2113822455492558850（guestName/guestPhone 仅本人）
```

B 前台：

```text
GET /admin/hotel/bookings
-> 整店可见
```

迁移脚本：

```text
scripts/migration/s5-hotel-store-migration.sql
幂等创建 store + 更新 htl_* + 行数校验
```

## 10. S5 准出边界

S5 后端准出：

```text
酒店 C 预订
B 确认/入住/现金收款/押金/现金退款/退房/夜审/清扫
C 本人订单隔离
```

明确 defer：

```text
C 在线支付、在线退款 -> S7 E2E 前补
owner：platform-order-service + common-payment-service
入口：C 收银台 / 在线退款申请
风险：当前 C 订单由 B 前台现金收款，不等于 C 在线支付完成
```

## 11. B/C 下单事务（2026-10-24）

`	ext
8 参 B 入口 @Transactional
9 参 C 入口 @Transactional（Controller 直接调用代理）
HotelBookingTransactionTest 1 PASS
`
