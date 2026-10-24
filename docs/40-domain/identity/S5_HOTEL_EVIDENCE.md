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
