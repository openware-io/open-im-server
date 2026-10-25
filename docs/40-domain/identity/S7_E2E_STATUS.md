# S7 E2E 执行状态
> 环境：Kind open-im-local、Chrome 155、Xiaomi MI 9（adb c4c90621）
> 时间：2026-10-24
> 状态：CONDITIONAL PASS（cash-only；本地发布 PASS（回滚未验证）；测试 APK 真机入口/详情/报价 PASS；在线支付/退款 deferred）

## 1. 已确认

### 浏览器

```text
http://127.0.0.1:30080/saas/ -> 200
平台管理员 admin 登录成功
-> /saas/#/admin/platform/tenants
```

### Android

```text
adb install -r app-debug.apk -> Success
package io.openware.openchat
启动进入登录页
```

### BFF 上下文问题

`a380-admin` 的 `cons_admin_account.platform_account_id` 仍是旧值 100，
tenant BFF `/internal/iam/account/100/contexts` 返回空：

```text
GET /api/v1/admin/contexts -> {"items":[]}
```

已改为 identity account `101`，重新登录返回 `platformAccountId=101`；
浏览器完整上下文选择尚未复测。

## 2. 未通过项（历史过程，当前见 CONDITIONAL PASS）

```text
B Web 租户上下文选择 / 酒店页面
C H5 会话重建与酒店下单页面
Android 登录 + 酒店入口
C/B 隔离、租户/门店隔离
酒店预订/支付/退款/前台/夜审/房态浏览器/真机全量用例
异常恢复
```

## 3. 已知环境问题

```text
C H5 直接打开 /a380/ 被 OAuth authorize 拒绝（会话过期）
Android 输入法/坐标操作导致登录密码未写入，应用被切到系统搜索
Chrome 无 Playwright driver，使用 CDP 手工驱动
```

## 4. 下一步

```text
1. 重建 C 会话并验证 /a380/ 页面
2. 复测 a380-admin B 上下文选择 -> 酒店页面
3. Android 用 uiautomator 精确定位输入密码并登录
4. 按 IDENTITY_TEST_PLAN 跑 P0/P1 用例
```

本文件不能作为 S7 通过证据。

## 5. B Web 酒店页面已通（2026-10-24）

修复：

```text
cons_admin_account.platform_account_id 100 -> 101
新增 MEMBERSHIP 1 role tenant.owner STORE 100/100/102
```

BFF 上下文：

```text
GET /api/v1/admin/contexts
-> 100:100:102 A380 Hotel HOTEL
-> 100:0:0 tenant
POST /api/v1/admin/context/select {"contextId":"100:100:102"} -> 200
```

酒店页面：

```text
/saas/#/admin/tenant/hotel
-> 酒店前台
-> 待入住1/已确认/今日到店
-> 待退房2
-> 任务总数3
-> 真实预订 HTL1792744369554696 / HTL1792747275893930
```

截图：

```text
docs/40-domain/identity/evidence/s7-b-hotel.png
```

## 6. C H5 当前阻塞（历史过程）

```text
Chrome 直接导航 /a380/ 无 Referer -> gateway unauthorized
加 Network.setExtraHTTPHeaders Referer=/a380/ 后 HTML 加载
H5 仍跳 /oauth/authorize；需继续定位 session 检查
```

## 7. Android 当前阻塞（历史过程）

```text
APK 安装/启动成功
登录页控件已定位
密码输入未写入 -> 登录提示“请输入用户名和密码”
设备被误触到系统搜索
```

（历史记录）S7 当时未通过；当前见 CONDITIONAL PASS。

## 8. Android 真机登录成功（2026-10-24）

APK 重建：

```text
--dart-define=OPEN_API_BASE=http://192.168.3.35:30080
--dart-define=OPEN_WS_URI=ws://192.168.3.35:30080/ws/im/v1
```

安装/登录：

```text
adb install -r app-debug.apk -> Success
登录 s3c001937 -> 消息页
服务 -> 酒店 -> WebView
```

酒店 WebView 仍显示演示数据：

```text
OpenChat酒店中心店
OpenChat酒店机场店
OpenChat酒店会展店
```

不是 A380 Hotel / 真实 API；S7 C 端酒店断言不成立。

截图：

```text
docs/40-domain/identity/evidence/s7-android-hotel-demo.png
```

## 9. C H5 入口已通但酒店页为演示

```text
/a380/ 首页加载
contextId=consumer:saas-a380-c:100:100:102
酒店页 -> OpenChat酒店中心店（演示）
```

需要把 C 端酒店页接到真实 `/business/hotel` API 才能过 S7。

## 10. C 端酒店已接真实 API（2026-10-24）

H5 新增真实酒店页：

```text
c-end/hotel-real.js
/service/hotel -> GET /business/hotel/catalog/hotels
real-hotel/{id} -> GET /business/hotel/catalog/hotels/{id}
POST /business/hotel/quotes
POST /business/hotel/bookings
```

浏览器 C 真实下单：

```text
A380 Hotel
Deluxe King
2026-10-25 ~ 2026-10-26
实时报价 ¥899.00
提交预订 -> HTL1792814765239386
status=PENDING_PAYMENT
```

DB：

```text
htl_booking id=2113844451563585538
booking_no=HTL1792814765239386
store_id=102
booked_by_account_id=101
order_id=11
ord_order total_amount=89900 DRAFT
```

截图：

```text
docs/40-domain/identity/evidence/s7-c-real-booking.png
```

Android 真机同样加载真实酒店：

```text
A380 Hotel / Deluxe King / 查询实时价格 ¥899.00
```

截图：

```text
s7-android-real-hotel.png
s7-android-real-quote.png
```

Android WebView 原生日期/输入控件仍妨碍自动提交，但页面和 API 已真实。

## 11. H5 Referrer 修复

```text
open-saas-mobile/nginx.conf
Referrer-Policy: no-referrer -> same-origin
```

重建镜像 `ghcr.io/openware-io/saas-mobile:2.4.0-s7` 并部署。

## 12. C 我的订单与支付（2026-10-24）

真实我的订单：

```text
GET /business/hotel/bookings
-> 仅 HTL1792814765239386
   Deluxe King / 2026-10-25 ~ 2026-10-26 / ¥899.00 / PENDING_PAYMENT
```

截图：

```text
docs/40-domain/identity/evidence/s7-c-my-orders.png
```

C 会话收款：

```text
POST /business/orders/11/collect
{"currencyCode":"CNY","payable":89900,"payments":[{"method":"CASH","amount":89900}]}
Idempotency-Key: S7-C-PAY-11
-> 200 remainingAmount=0
```

DB：

```text
ord_order id=11 status=COMPLETED total=89900 paid=89900 customer_id=1
pay_collect id=5 order_id=11 state=CONFIRMED idempotency_key=S7-C-PAY-11
```

说明：支付方式为 CASH，不是在线商户；在线支付/在线退款仍未完成。

## 13. C→B 隔离

C 会话 token 调 B 接口：

```text
GET /admin/hotel/bookings
-> 403 PERMISSION_DENIED 缺少权限 hotel.booking.view
```

C token 有 hotel.booking.view，但 audience 为 saas-c，B 端点仍拒绝。

## 14. B 退款账务联动（2026-10-24）

问题：退款置 REFUNDED 后 `ord_order.paid_amount/refundable_amount` 未回冲。

修复：

```text
OrderBillingRepository.markRefunded
-> UPDATE ord_order
   paid_amount = GREATEST(0, paid_amount - refund)
   refundable_amount = GREATEST(0, refundable_amount - refund)
RefundApplicationService.markRefunded 调用并失败回滚
```

真实：

```text
order 11 paid 89900
refund 2 10000 REFUNDED
refund 3 10000 REFUNDED
refund 4 10000 REFUNDED
refund 5 60000 APPROVED（修复前，已置 REJECTED）
refund 6 60000 审批 -> 422 REFUND_EXCEEDS_PAID

order 11 paid/refundable = 59900
sum(refunded)=30000
89900 - 30000 = 59900
```

新增：`approveRefund` 校验 `approvedAmount <= order.paidOrZero()`。

部署：`common-payment-service:2.4.0-SNAPSHOT`（此前 deployment 仍是 2.2.0，已切 2.4.0）。

## 15. 隔离矩阵（2026-10-24）

```text
C token -> GET /admin/hotel/bookings
-> 403 CLIENT_AUDIENCE_FORBIDDEN

B token -> GET /business/hotel/catalog/hotels
-> 403 CLIENT_AUDIENCE_FORBIDDEN

无 token -> /admin/hotel/bookings
-> 403 PERMISSION_DENIED

篡改 C token -> 401 Unauthorized

B store 100 token -> confirm store 102 booking
-> 404 HOTEL_BOOKING_NOT_FOUND
```

## 16. 退款账实

```text
order 11 paid 89900
refund 2/3/4 各 10000 REFUNDED
order paid/refundable 59900
sum(refunded)=30000
89900-30000=59900

refund 6 60000 approve -> 422 REFUND_EXCEEDS_PAID
```

## 17. 在线支付外部依赖

```text
pay_channel_config:
ALIPAY enabled=0
WECHAT enabled=0
STRIPE enabled=0
pay_channel_provider 无记录
```

没有可用真实在线商户；C 在线支付/在线退款无法在本地产生真实渠道流水。
当前只完成 CASH 收款 + 线下退款登记（PENDING→APPROVED→REFUNDED）与订单回冲。

## 18. 预订 hold 过期（异常用例）

C 预订 HTL1792814765239386 创建于 12:05，hold 15 分钟。
B 在 12:31 调 confirm：

```text
POST /admin/hotel/bookings/2113844451563585538/confirm
-> 409 BOOKING_STATE_INVALID 当前预订不能确认
```

说明：hold 过期后不允许确认，符合预期；在线支付必须在 hold 窗口内完成并触发确认。
当前 C 收款由 B 手工补收，未在 hold 窗口内确认，因此该单保持 PENDING_PAYMENT。

## 19. P0 修复（2026-10-24 晚）

### C 下单自动绑定 customer_id

```text
HotelBookingApplicationService -> MemberLookupProvider
-> createHotelReceivable(..., customerId)

booking HTL1792855173541441
booking id=2114013936307535874
order_id=13
customer_id=1（自动写入，无手工 SQL）
```

### 支付后自动确认预订

```text
HotelBookingPaymentReconciler
@Scheduled 60s
JOIN ord_order status=COMPLETED AND paid_amount>=total_amount
-> htl_booking PENDING_PAYMENT -> CONFIRMED
-> holds HELD -> SOLD
```

真实：

```text
order 13 paid 89900 COMPLETED
booking 2114013936307535874 -> CONFIRMED
```

### 并发退款保护

```text
approveRefund:
approvedAmount + 同订单其他 APPROVED 金额 <= order.paid
```

真实：

```text
refund 7 60000 -> APPROVED 200
refund 8 60000 -> 422 REFUND_EXCEEDS_PAID
```

### 部署

```text
build-saas-release -> s7-p0-manifest / s7-refund-manifest
k8s-scoped -> EXIT=0
```

## 20. 仍待完成

```text
Android 全 C 流（下单/支付/订单）
ERR-001/002 真实故障注入
SEC 全矩阵
HAR/logcat/trace
本地 Kind 发布
数据 seed/migration
```

## 21. ERR-001/002 transport 级证据（2026-10-24）

payment 缩容到 0：

```text
POST :29145/business/orders/14/collect
-> 000 / Empty reply from server

ord_order 14:
status=DRAFT
paid_amount=0
total_amount=89900
```

退款服务不可用：

```text
pay_refund 7:
status=APPROVED
approved_amount=60000
未被错误置为 REFUNDED
```

说明：这是 transport/服务不可用级别证据，未覆盖 HTTP 5xx 包装与网关超时页面。

## 22. Android 真实下单/我的订单（2026-10-24）

真机操作：

```text
服务 -> 酒店 -> A380 Hotel
Deluxe King
2026-10-25 ~ 2026-10-26
实时报价 ¥899.00
入住人 AndroidGuest / 13800000014
提交预订
-> HTL1792855990895433
-> PENDING_PAYMENT
```

我的订单：

```text
HTL1792855990895433 PENDING_PAYMENT
HTL1792855592481956 PENDING_PAYMENT
HTL1792855173541441 CONFIRMED
HTL1792816675868789 CANCELLED
...
```

截图：

```text
docs/40-domain/identity/evidence/s7-android-booking.png
docs/40-domain/identity/evidence/s7-android-orders.png
```

APP-011 / APP-013 已过；APP-012 在线支付仍 deferred。

## 23. 可复现 seed

```text
scripts/migration/s7-local-e2e-seed.sql
```

幂等覆盖：

```text
cons_admin_account platform_account_id=101
STAFF/CUSTOMER membership account101
cst_member account101
store 102/100 owner bindings
未来 14 天房态
```

连续执行两次计数不变：

```text
store=1 staff=1 customer=1 inventory=18
```

## 24. SEC-010 PII 脱敏（2026-10-24）

实现：

```text
HotelBookingApplicationService.list/listByAccount
无 member.pii.view 时：
  guestName -> 首字 + **
  guestPhone -> 前3 + **** + 后4
C 本人订单（booked_by_account_id == context.accountId）保持完整
```

V17：

```text
store.manager / store.cashier 授予 member.pii.view
无此权限角色仍掩码
```

单测：

```text
HotelBookingPiiTest PASS
张三 -> 张**
13800000014 -> 138****0014
```

## 25. ERR HTTP 5xx（2026-10-24）

payment 缩容 0，管理会话经 gateway 收款：

```text
POST /api/v1/business/orders/15/collect
-> HTTP/1.1 500 Internal Server Error
requestId=e82cb6db-7
ord_order 15: DRAFT / paid=0
```

证据：

```text
evidence/s7-trace/err-001-payment-down.headers
evidence/s7-trace/err-001-payment-down.body
```

gateway 修正：

```text
旧：下游不可用被 onErrorResume 误映射为 401 SAAS_SESSION_UNAVAILABLE
新：只捕获 Redis 会话读取错误；下游异常保留 5xx
```

## 6. B 酒店前台 UI（2026-10-24）

路由：

```text
http://127.0.0.1:30080/saas/#/admin/tenant/hotel
```

真实页面：

```text
酒店前台
待入住 2 / 在住 0 / 待退房 2
任务 4 条
HTL1792855173541441 已确认
HTL1792744369554696 已确认
```

截图：

```text
evidence/s7-b-hotel-ui.png
```

限制：

```text
点击“办理入住”后返回“酒店预订不存在”
（预订日期 2026-10-27，非当日；未完成 UI 动作闭环）
```

## 7. B 前台入住闭环（2026-10-24）

发现真实前端问题：

```text
Long 2114013936307535874 在浏览器 JS 中变成 2114013936307536000
-> /bookings/2114013936307536000/checkin -> 404
```

修复：

```text
HotelFrontDeskController.BookingView.id
@JsonFormat(shape = STRING)
```

部署后 UI：

```text
点击“办理入住”
-> booking 2114013936307535874 IN_HOUSE
-> room 0801 OCCUPIED
-> htl_stay 生成
-> 页面显示 在住 1
```

截图：

```text
evidence/s7-b-checkin.png
```

未完成：

```text
“办理退房” UI 点击未产生退房请求
booking 仍 IN_HOUSE
```

## 8. B 前台退房闭环（2026-10-24）

发现真实账务缺口：

```text
C 订单已 pay 89900
酒店 folio paid=0
checkout -> 409 OUTSTANDING_BALANCE
```

修复：

```text
HotelStayApplicationService.checkOut
if folio.paid < charge:
  booking.orderId 的 ord_order.paid >= charge
  -> 写 PAYMENT / ORDER_PREPAID 抵扣项
  -> folio.paid = charge
```

真实 UI：

```text
点击“办理退房”
-> booking 2114013936307535874 CHECKED_OUT
-> room 0801 DIRTY
-> 在住 0
```

截图：

```text
evidence/s7-b-checkout.png
```

## 9. B 客房清扫 / 夜审 UI（2026-10-24）

### 清扫

```text
点击“开始清扫” -> htl_housekeeping_task IN_PROGRESS
点击“完成清扫” -> DONE
room 0801 DIRTY -> ACTIVE
```

截图：

```text
evidence/s7-b-housekeeping.png
```

### 夜审

```text
点击“夜审” -> 输入营业日 -> 确定
-> toast 夜审完成
-> htl_night_audit 新增记录
```

真实：

```text
business_date=2026-11-01
in_house_count=1
due_out_count=1
```

截图：

```text
evidence/s7-b-night-audit.png
```

## 10. C H5 取消预订（2026-10-24）

新增：

```text
POST /business/hotel/bookings/{id}/cancel
ownership: 只能取消本人 booking
H5: PENDING_PAYMENT 订单显示“取消预订”
```

真实：

```text
HTL1792861923013715 PENDING_PAYMENT
点击取消 -> CANCELLED
```

截图：

```text
evidence/s7-c-cancel.png
```

C 在线退款仍 deferred；线下退款由 B 前台处理。

## 11. B 退款 UI（2026-10-24）

真实：

```text
pay_refund 9  10000 PENDING
退款处理 -> 批准 -> APPROVED
登记退款 -> REFUNDED
ord_order 13 paid 89900 -> 79900
```

截图：

```text
evidence/s7-b-refund-ui.png
```

说明：线上退款仍 deferred；本次为线下退款登记。

## 12. Android 同单现金闭环（2026-10-24）

真机：

```text
A380 Hotel / 10-26 ~ 10-27 / ¥899
HTL1792863984834312
PENDING_PAYMENT
```

B 收款 UI：

```text
发起收款 订单 18 / 现金 ¥899
-> 收款成功：现金 ¥899.00
```

结果：

```text
ord_order 18: COMPLETED / paid 89900
htl_booking: CONFIRMED
Android 我的酒店订单: CONFIRMED
```

截图：

```text
evidence/s7-android-cash-loop.png
```

## 13. 跨门店收款修复（2026-10-24）

发现：

```text
B store100 token 收款 order21（store102）曾返回 200
pay_collect.store_id=100 / ord_order.store_id=102
```

修复：

```text
OrderBillingRepository.findStoreId
CollectApplicationService: order.store_id != context.store_id -> 404
```

回归：

```text
store100 -> order21 404 ORDER_NOT_FOUND
store102 -> order21 200
order21 store102 DRAFT -> COMPLETED
```

证据：

```text
evidence/s7-trace/sec-011-cross-store-pay.*
evidence/s7-trace/sec-012-correct-store-pay.*
```

## 14. 跨门店退款修复（2026-10-24）

修复：

```text
requestRefund: order.store_id 必须等于 context.store_id
approve/reject/mark: refund.store_id 必须等于当前门店
order.store_id 必须等于 refund.store_id
缺失/null fail-closed
```

真实：

```text
store100 request order21 -> 404 ORDER_NOT_FOUND
store100 approve refund10 -> 404 REFUND_NOT_FOUND
store100 mark refund10 -> 404 REFUND_NOT_FOUND
store102 approve/mark -> 200
order21 paid 89900 -> 79900
```

证据：

```text
sec-013-cross-store-refund*.har
sec-014-correct-store-refund-*.har
sec-013-014-db.txt
```

注：order18 的 `pay_collect.store_id=100` / `ord_order.store_id=102` 是跨店修复前的历史数据，保留为负例，不作为“正确门店收款”的正例；修复后以 SEC-011/012 的 store100 404 / store102 200 为准。

## 15. null-scope fail-closed（2026-10-24）

修复：

```text
CollectApplicationService:
  orderStoreId == null || storeId == null || !equals -> 404

RefundApplicationService.requireRefundStore:
  context 非 PLATFORM 且 storeId == null -> 404
  context.storeId != refund.storeId -> 404
  order.storeId != refund.storeId -> 404
```

单测：

```text
payment 149 tests PASS
approveRefund_rejectsNullStoreContext
```

说明：platform scope 是显式例外；普通 B token 无门店上下文一律拒绝。

## 16. B 收款 UI / context null 硬化（2026-10-24）

B 收款 UI：

```text
发起收款 订单22 / 现金 ¥899 -> 收款成功
ord_order 22 store102 COMPLETED paid 89900
pay_collect 22 store102 CONFIRMED
```

截图：

```text
evidence/s7-b-collect-ui.png
evidence/s7-b-collect-success.png
```

context null：

```text
requireRefundStore: context == null && orderBillingRepository != null -> 404
```

状态：

```text
S7 cash-only E2E: CONDITIONAL PASS
本地发布/回滚、测试 APK 真机验证进行中
C 在线支付/退款: deferred
完整 S7: 不成立
```
