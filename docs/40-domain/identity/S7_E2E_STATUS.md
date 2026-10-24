# S7 E2E 执行状态

> 环境：Kind `open-im-local`、Chrome 155、Xiaomi MI 9（adb `c4c90621`）
> 时间：2026-10-24
> 状态：进行中，未通过

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

## 2. 未通过项

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

## 6. C H5 当前阻塞

```text
Chrome 直接导航 /a380/ 无 Referer -> gateway unauthorized
加 Network.setExtraHTTPHeaders Referer=/a380/ 后 HTML 加载
H5 仍跳 /oauth/authorize；需继续定位 session 检查
```

## 7. Android 当前阻塞

```text
APK 安装/启动成功
登录页控件已定位
密码输入未写入 -> 登录提示“请输入用户名和密码”
设备被误触到系统搜索
```

S7 仍未通过。

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
