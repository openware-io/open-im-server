# S7 证据索引

> 2026-10-24
> 目的：把用例 ID 与真实请求/响应/HAR/trace/DB 结果对齐。

## 1. 浏览器 / 真机

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| BROWSER-B 登录/门店/酒店前台 | PASS | `evidence/s7-b-hotel.png`、`s7-b-hotel-ui.png` |
| BROWSER-B 办理入住 | PASS | `evidence/s7-b-checkin.png`、booking IN_HOUSE |
| BROWSER-C 真实酒店目录 | PASS | `evidence/s7-c-home.png` |
| BROWSER-C 真实预订 | PASS | `evidence/s7-c-real-booking.png`、`s7-c-my-orders.png` |
| APP-011 Android 真实下单 | PASS | `evidence/s7-android-booking.png` |
| APP-013 Android 我的订单 | PASS | `evidence/s7-android-orders.png` |
| Android 真实酒店/报价 | PASS | `evidence/s7-android-real-hotel.png`、`s7-android-real-quote.png` |
| Android 演示图 | 负例 | `evidence/negative/s7-android-hotel-demo.png` |

## 2. 安全

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| SEC-001/002 跨门店 | 404 | API/DB + `S7_SECURITY_MATRIX.md` |
| SEC-003/004 篡改 token | 401 | API |
| SEC-005 C/B cookie | 403 | API |
| SEC-006 平台角色越权 | 403 | S6 记录 |
| SEC-007 过期 token | 401 | `evidence/s7-trace/sec-007-expired.*` |
| SEC-008 成员禁用 | 401 | order/payment/tenant/admin/identity/gateway 200→401 |
| SEC-009 版本变化 | 401 | version+1 → 401 |
| SEC-010 PII | 掩码 | `evidence/s7-trace/sec-010-nopii.*`、`HotelBookingPiiTest` |

## 3. 请求级

| 项 | 证据 |
| --- | --- |
| B 管理面 HAR | `evidence/s7-trace/admin-hotel-bookings.har` |
| C 端 HAR | `evidence/s7-trace/c-hotel.har` |
| C 端 trace | `evidence/s7-trace/c-hotel-trace.json` |
| Android logcat | `evidence/s7-trace/android-hotel.log` |
| 管理面 200/401 requestId | `S7_REQUEST_EVIDENCE.md` |
| ERR-001 HTTP 500 | `evidence/s7-trace/err-001-payment-down.*` |
| gateway 401 | `evidence/s7-trace/gateway-authz-disabled.*` |
| identity 403 | `evidence/s7-trace/identity-session-disabled.*` |

## 4. 账务 / 状态

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 现金收款 89900 | 200 | order 13 |
| 支付后确认 booking | CONFIRMED | booking 2114013936307535874 |
| 退款回冲 | 59900 | order 11 |
| 并发超退 | 422 | refund 8 |
| hold 过期确认 | 409 | booking HTL1792814765239386 |
| payment 停服 | 500 + DRAFT/paid=0 | order 15 |

## 5. 仍缺

```text
B/C 浏览器前台全动作 HAR/截图
ACK/ACR/回滚
正式签名 APK
SEC-001..006 逐条 HAR 附件
ERR 5xx 页面级截图
在线支付/退款 deferred
```
